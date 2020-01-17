package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.{Concurrent, Fiber, Resource, Sync, Timer}
import cats.effect.concurrent.{Ref, Semaphore}
import io.casperlabs.casper.consensus.{Block, BlockSummary, Era}
import io.casperlabs.casper.util.DagOperations, DagOperations.Key
import io.casperlabs.casper.highway.EraRuntime.Agenda
import io.casperlabs.comm.gossiping.Relaying
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage, FinalityStorageReader}
import io.casperlabs.storage.era.EraStorage

class EraSupervisor[F[_]: Concurrent: EraStorage: Relaying](
    // Once the supervisor is shut down, reject incoming messages.
    isShutdownRef: Ref[F, Boolean],
    // Help ensure we only create one runtime per era.
    semaphore: Semaphore[F],
    runtimesRef: Ref[F, Map[BlockHash, EraRuntime[F]]],
    scheduleRef: Ref[F, Map[(BlockHash, Agenda.DelayedAction), Fiber[F, Unit]]],
    eraTreeRef: Ref[F, Map[BlockHash, Set[BlockHash]]],
    makeRuntime: Era => F[EraRuntime[F]],
    scheduler: TickScheduler[F],
    forkChoiceManager: EraSupervisor.ForkChoiceManager[F]
) {

  private def shutdown(): F[Unit] =
    for {
      _        <- isShutdownRef.set(true)
      schedule <- scheduleRef.get
      _        <- schedule.values.toList.map(_.cancel.attempt).sequence.void
    } yield ()

  /** Handle a block coming from the DownloadManager. No need to broadcast the block itself,
    * it will be gossiped if it's successfully validated. Only need to broadcast responses. */
  def validateAndAddBlock(block: Block): F[Unit] =
    for {
      _       <- ensureNotShutdown
      message <- Sync[F].fromTry(Message.fromBlock(block))
      runtime <- getOrStart(message.keyBlockHash)
      _ <- runtime.validate(message).value.flatMap {
            _.fold(
              error =>
                Sync[F].raiseError[Unit](
                  new IllegalArgumentException(s"Could not validate block against era: $error")
                ),
              _ => ().pure[F]
            )
          }

      // TODO: Validate and execute the block, store it in the block store and DAG store.

      // Tell descendant eras for the next time they create a block that this era received a message.
      _ <- propagateLatestMessageToDescendantEras(message)

      // See what reactions the protocol dictates.
      events <- runtime.handleMessage(message).written
      _      <- handleEvents(events)
    } yield ()

  private def ensureNotShutdown: F[Unit] =
    isShutdownRef.get.ifM(
      Sync[F]
        .raiseError(new IllegalStateException("EraSupervisor is already shut down.")),
      Sync[F].unit
    )

  private def getOrStart(keyBlockHash: BlockHash): F[EraRuntime[F]] =
    runtimesRef.get >>= {
      _.get(keyBlockHash).fold {
        semaphore.withPermit {
          runtimesRef.get >>= {
            _.get(keyBlockHash).fold(start(keyBlockHash))(_.pure[F])
          }
        }
      }(_.pure[F])
    }

  /** Create a runtime and schedule its initial agenda. */
  private def start(keyBlockHash: BlockHash): F[EraRuntime[F]] =
    for {
      // We should already have seen the switch block that created this block's era,
      // or it should be in the genesis era. If we can't find it, the Download Manager
      // would not have given the block to us, or the key block is invalid.
      era     <- EraStorage[F].getEraUnsafe(keyBlockHash)
      runtime <- makeRuntime(era)
      agenda  <- runtime.initAgenda
      _       <- start(runtime, agenda)
    } yield runtime

  private def start(runtime: EraRuntime[F], agenda: Agenda): F[Unit] = {
    val key = runtime.era.keyBlockHash
    for {
      _ <- runtimesRef.update { rs =>
            // Sanity check that the semaphore is applied.
            require(!rs.contains(key), "Shouldn't start eras more than once!")
            rs.updated(key, runtime)
          }
      childEras <- EraStorage[F].getChildEras(key)
      _ <- childEras.toList.traverse { era =>
            addChild(key, era.keyBlockHash)
          }
      _ <- schedule(runtime, agenda)
    } yield ()
  }

  private def schedule(runtime: EraRuntime[F], agenda: Agenda): F[Unit] =
    agenda.traverse(schedule(runtime, _)).void

  private def schedule(runtime: EraRuntime[F], delayed: Agenda.DelayedAction): F[Unit] = {
    val key = (runtime.era.keyBlockHash, delayed)
    for {
      fiber <- scheduler.scheduleAt(delayed.tick) {
                for {
                  _                <- scheduleRef.update(_ - key)
                  (events, agenda) <- runtime.handleAgenda(delayed.action).run
                  _                <- handleEvents(events)
                  _                <- schedule(runtime, agenda)
                } yield ()
              }
      _ <- scheduleRef.update { s =>
            // Sanity check that we're not leaking fibers.
            require(!s.contains(key), "Shouldn't schedule the same thing twice!")
            s.updated(key, fiber)
          }
    } yield ()
  }

  private def handleEvents(events: Vector[HighwayEvent]): F[Unit] =
    events.traverse(handleEvent(_)).void

  private def handleEvent(event: HighwayEvent): F[Unit] = event match {
    case HighwayEvent.CreatedEra(era) =>
      for {
        _ <- addChild(era.parentKeyBlockHash, era.keyBlockHash)
        _ <- getOrStart(era.keyBlockHash)
      } yield ()
    case HighwayEvent.CreatedLambdaMessage(m)  => handleCreatedMessage(m)
    case HighwayEvent.CreatedLambdaResponse(m) => handleCreatedMessage(m)
    case HighwayEvent.CreatedOmegaMessage(m)   => handleCreatedMessage(m)
  }

  /** Handle a message created by this node. */
  private def handleCreatedMessage(message: Message): F[Unit] =
    for {
      _ <- Relaying[F].relay(List(message.messageHash))
      _ <- propagateLatestMessageToDescendantEras(message)
    } yield ()

  /** Add a parent-child era association to the tree of eras, so that we can update
    * the descendant eras when new messages come in to the ancestors, which is required
    * for things like rewarding voting-only ballots that are created in the parent,
    * with the rewards being added to some blocks in the child era.
    */
  private def addChild(parentKeyBlockHash: BlockHash, childKeyBlockHash: BlockHash): F[Unit] =
    eraTreeRef.update { tree =>
      tree.updated(parentKeyBlockHash, tree(parentKeyBlockHash) + childKeyBlockHash)
    }

  /** Update descendant eras' latest messages. */
  private def propagateLatestMessageToDescendantEras(message: Message): F[Unit] =
    for {
      childEras <- eraTreeRef.get.map(_(message.keyBlockHash))
      _ <- childEras.toList.traverse { childKeyBlockHash =>
            forkChoiceManager.updateLatestMessage(childKeyBlockHash, message)
          }
    } yield ()
}

object EraSupervisor {

  def apply[F[_]: Concurrent: Timer: EraStorage: DagStorage: FinalityStorageReader: Relaying](
      conf: HighwayConf,
      genesis: BlockSummary,
      maybeMessageProducer: Option[MessageProducer[F]],
      initRoundExponent: Int,
      isSynced: F[Boolean]
  ): Resource[F, EraSupervisor[F]] =
    Resource.make {
      for {
        isShutdownRef <- Ref.of(false)
        runtimesRef   <- Ref.of(Map.empty[BlockHash, EraRuntime[F]])
        scheduleRef   <- Ref.of(Map.empty[(BlockHash, Agenda.DelayedAction), Fiber[F, Unit]])
        eraTreeRef    <- Ref.of(Map.empty[BlockHash, Set[BlockHash]].withDefaultValue(Set.empty))
        semaphore     <- Semaphore[F](1)

        implicit0(forkChoiceManager: ForkChoiceManager[F]) = new ForkChoiceManager[F]()

        makeRuntime = (era: Era) =>
          EraRuntime.fromEra[F](
            conf,
            era,
            maybeMessageProducer,
            initRoundExponent,
            isSynced
          )

        scheduler = new TickScheduler[F](conf.tickUnit)

        supervisor = new EraSupervisor[F](
          isShutdownRef,
          semaphore,
          runtimesRef,
          scheduleRef,
          eraTreeRef,
          makeRuntime,
          scheduler,
          forkChoiceManager
        )

        // Make sure we have the genesis era in storage. We'll resume if it's the first one.
        genesisEra = EraRuntime.genesisEra(conf, genesis)
        _          <- EraStorage[F].addEra(genesisEra)

        // Resume currently active eras.
        activeEras <- collectActiveEras[F](makeRuntime)
        _ <- activeEras.traverse {
              case (runtime, agenda) => supervisor.start(runtime, agenda)
            }
      } yield supervisor
    }(_.shutdown())

  // Fork choice should be something that we can update for each child era when there are new messages coming in.
  class ForkChoiceManager[F[_]] extends ForkChoice[F] {

    /** Tell the fork choice that deals with a given era that an ancestor era has a new message. */
    def updateLatestMessage(
        // The era in which we want to update the latest message.
        keyBlockHash: BlockHash,
        // The latest message in the ancestor era that must be taken into account from now.
        message: Message
    ): F[Unit] = ???

    override def fromKeyBlock(keyBlockHash: BlockHash): F[ForkChoice.Result] = ???
    override def fromJustifications(
        keyBlockHash: BlockHash,
        justifications: Set[BlockHash]
    ): F[ForkChoice.Result] = ???
  }

  // Return type for the initialization of active eras along the era tree.
  type EraWithAgenda[F[_]] = (EraRuntime[F], EraRuntime.Agenda)

  // Helper for traversing the era tree.
  implicit def `Key[EraWithAgenda]`[F[_]] = Key.instance[EraWithAgenda[F], BlockHash] {
    case (runtime, _) => runtime.era.keyBlockHash
  }

  /** Walk up the era tree, starting from the tips, and collect any era that's
    * not finished yet. We know they are not finished because they have something
    * they want to do. If there's nothing, we'll have to rely on incoming messages
    * to establish the missing links for us.
    */
  def collectActiveEras[F[_]: Sync: EraStorage](
      makeRuntime: Era => F[EraRuntime[F]]
  ): F[List[EraWithAgenda[F]]] = {
    // Eras without agendas are finished.
    def selectActives(eras: List[EraWithAgenda[F]]) =
      eras.filterNot(_._2.isEmpty)

    for {
      tips <- EraStorage[F].getChildlessEras

      activeTips <- tips.toList.traverse { era =>
                     for {
                       runtime <- makeRuntime(era)
                       agenda  <- runtime.initAgenda
                     } yield runtime -> agenda
                   } map { selectActives }

      active <- DagOperations
                 .bfTraverseF[F, EraWithAgenda[F]](activeTips) {
                   case (runtime, _) if !runtime.era.parentKeyBlockHash.isEmpty =>
                     for {
                       parentEra     <- EraStorage[F].getEraUnsafe(runtime.era.parentKeyBlockHash)
                       parentRuntime <- makeRuntime(parentEra)
                       parentAgenda  <- parentRuntime.initAgenda
                     } yield selectActives(List(parentRuntime -> parentAgenda))

                   case _ =>
                     List.empty.pure[F]
                 }
                 .toList
    } yield active
  }
}
