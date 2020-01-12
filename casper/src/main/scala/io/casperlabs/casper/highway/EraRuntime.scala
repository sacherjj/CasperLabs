package io.casperlabs.casper.highway

import cats._
import cats.data.{EitherT, WriterT}
import cats.implicits._
import cats.effect.{Clock, Sync}
import cats.effect.concurrent.Ref
import com.google.protobuf.ByteString
import java.time.Instant
import io.casperlabs.casper.consensus.{Block, BlockSummary, Era}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.casper.util.DagOperations
import scala.util.Random

/** Class to encapsulate the message handling logic of messages in an era.
  *
  * It can create blocks/ballots and persist them, even new eras,
  * but it should not have externally visible side effects, i.e.
  * not communicate over the network.
  *
  * Persisting messages is fine: if we want to handle messages concurrently
  * we have to keep track of the validator sequence number and make sure
  * we cite our own previous messages.
  *
  * The handler methods will return a list of domain events,
  * and a list of future actions in the form of an ADT;
  * it's up to the supervisor protocol to broadcast them
  * and schedule followup actions.
  *
  * This should make testing easier: the return values are not opaque.
  */
class EraRuntime[F[_]: MonadThrowable: Clock: EraStorage: ForkChoice](
    conf: HighwayConf,
    val era: Era,
    leaderFunction: LeaderFunction,
    roundExponentRef: Ref[F, Int],
    maybeMessageProducer: Option[MessageProducer[F]],
    // Indicate whether the initial syncing mechanism that runs when the node is started
    // is still ongoing, or it has concluded and the node is caught up with its peers.
    // Before that, responding to messages risks creating an equivocation, in case some
    // state was somehow lost after a restart. It could also force others to deal with
    // messages build on blocks long gone and check a huge swathe of the DAG for merge
    // conflicts.
    isSynced: => F[Boolean],
    dag: DagRepresentation[F],
    // Random number generator used to pick the omega delay.
    rng: Random = new Random()
) {
  import EraRuntime._, Agenda._

  type HWL[A] = HighwayLog[F, A]
  private val noop = HighwayLog.unit[F]

  val startTick = Ticks(era.startTick)
  val endTick   = Ticks(era.endTick)
  val start     = conf.toInstant(startTick)
  val end       = conf.toInstant(endTick)

  val bookingBoundaries =
    conf.criticalBoundaries(start, end, delayDuration = conf.bookingDuration)

  val keyBoundaries =
    conf.criticalBoundaries(start, end, delayDuration = conf.keyDuration)

  /** When we handle an incoming block or create a new one we may have to do additional work:
    * - if the block is a booking block, we have to execute the auction to pick the validators for the upcoming era
    * - when see a switch block, we have to look for a main ancestor which was a key block, to decide which era it belongs to.
    * These blocks can be identified by their round ID being after a boundary (a deadline),
    * while their main parent was still before the deadline.
    */
  private def isBoundary(boundaries: List[Instant])(
      mainParentBlockRound: Instant,
      blockRound: Instant
  ) = boundaries.exists(isCrossing(_)(mainParentBlockRound, blockRound))

  val isBookingBoundary = isBoundary(bookingBoundaries)(_, _)
  val isKeyBoundary     = isBoundary(keyBoundaries)(_, _)

  /** Switch blocks are the first blocks which are created _after_ the era ends.
    * They are still created by the validators of this era, and they signal the
    * end of the era. Switch blocks are what the child era is going to build on,
    * however, the validators of _this_ era are the ones that can finalize it by
    * building ballots on top of it. They cannot build more blocks on them though.
    */
  val isSwitchBoundary = isCrossing(end)(_, _)

  private implicit class MessageOps(msg: Message) {
    def roundInstant = conf.toInstant(Ticks(msg.roundId))
  }

  /** Current tick based on wall clock time. */
  private def currentTick =
    Clock[F].realTime(conf.tickUnit).map(Ticks(_))

  /** Check if the era is finished yet, including the post-era voting period. */
  private def isOverAt(tick: Ticks): F[Boolean] =
    if (tick < endTick) false.pure[F]
    else {
      conf.postEraVotingDuration match {
        case HighwayConf.VotingDuration.FixedLength(duration) =>
          (end plus duration).isBefore(conf.toInstant(tick)).pure[F]

        case HighwayConf.VotingDuration.SummitLevel(_) =>
          ???
      }
    }

  /** Calculate the beginning and the end of this round,
    * based on the current tick and the round length. */
  private def roundBoundariesAt(tick: Ticks): F[(Ticks, Ticks)] =
    // TODO (round_parameter_storage): We should be able to tell about each past tick what the exponent at the time was.
    roundExponentRef.get map { exp =>
      // The era start may not be divisible by the round length.
      // We should be okay to use `Ticks.roundBoundaries` here if we follow the
      // rules of when we can update the round exponent, that is, when the higher
      // and lower speeds align themselves.
      Ticks.roundBoundaries(startTick, exp)(tick)
    }

  /** Only produce messages once we are synced. */
  private def ifSynced(block: HWL[Unit]): HWL[Unit] =
    HighwayLog
      .liftF(isSynced)
      .ifM(
        block,
        HighwayLog.unit[F]
      )

  private def createLambdaResponse(
      messageProducer: MessageProducer[F],
      // TODO (NODE-1116): Lambda message will be a ballot during voting.
      lambdaMessage: Message.Block
  ): HWL[Unit] = ifSynced {
    for {
      b <- HighwayLog.liftF {
            for {
              // We need to cite the lambda message, and our own latest message.
              // NOTE: The latter will be empty until the validator produces something in this era.
              tips                    <- dag.latestInEra(era.keyBlockHash)
              validatorLatestMessages <- tips.latestMessageHash(messageProducer.validatorId)
              justifications = List(
                PublicKey(lambdaMessage.validatorId) -> Set(lambdaMessage.messageHash),
                messageProducer.validatorId          -> validatorLatestMessages
              ).filterNot(_._2.isEmpty).toMap
              // See what the fork choice is, given the lambda and our own latest message, that is our target.
              // In the voting period the lambda message is a ballot, so can't be a target,
              // but in any case our own latest message might point at something with more weight already.
              choice <- ForkChoice[F].fromJustifications(
                         justifications.values.flatten.toSet
                       )
              message <- messageProducer.ballot(
                          eraId = era.keyBlockHash,
                          roundId = Ticks(lambdaMessage.roundId),
                          target = choice.mainParent.messageHash,
                          justifications = justifications
                        )
            } yield message
          }
      _ <- HighwayLog.tell[F] {
            HighwayEvent.CreatedLambdaResponse(b)
          }
    } yield ()
  }

  private def createLambdaMessage(
      messageProducer: MessageProducer[F],
      roundId: Ticks
  ): HWL[Unit] = ifSynced {
    for {
      b <- HighwayLog.liftF {
            for {
              choice <- ForkChoice[F].fromKeyBlock(era.keyBlockHash)
              // TODO (NODE-1116): Create ballot during voting-only.
              message <- messageProducer.block(
                          eraId = era.keyBlockHash,
                          roundId = roundId,
                          mainParent = choice.mainParent.messageHash,
                          justifications = choice.justificationsMap,
                          isBookingBlock = isBookingBoundary(
                            choice.mainParent.roundInstant,
                            conf.toInstant(roundId)
                          )
                        )
            } yield message
          }
      _ <- HighwayLog.tell[F] {
            HighwayEvent.CreatedLambdaMessage(b)
          }
      _ <- handleCriticalBlocks(b)
    } yield ()
  }

  private def createOmegaMessage(
      messageProducer: MessageProducer[F],
      roundId: Ticks
  ): HWL[Unit] = ifSynced {
    for {
      b <- HighwayLog.liftF {
            for {
              choice <- ForkChoice[F].fromKeyBlock(era.keyBlockHash)
              message <- messageProducer.ballot(
                          eraId = era.keyBlockHash,
                          roundId = roundId,
                          target = choice.mainParent.messageHash,
                          justifications = choice.justificationsMap
                        )
            } yield message
          }
      _ <- HighwayLog.tell[F](
            HighwayEvent.CreatedOmegaMessage(b)
          )
    } yield ()
  }

  /** Trace the lineage of the switch block back to find a key block,
    * then the corresponding booking block.
    */
  private def createEra(
      switchBlock: Message
  ): HWL[Unit] = {
    val keyBlockBoundary     = end minus conf.keyDuration
    val bookingBlockBoundary = end minus conf.bookingDuration

    // Trace back to the corresponding key block.
    val keyBlockF = findBlockCrossingBoundary(switchBlock, isCrossing(keyBlockBoundary))

    val maybeChildEra = keyBlockF flatMap { keyBlock =>
      // Checking if the era exist; if it does, we don't need to look up the booking block.
      EraStorage[F]
        .containsEra(keyBlock.messageHash)
        .ifM(
          none[Era].pure[F],
          for {
            bookingBlock <- findBlockCrossingBoundary(
                             keyBlock,
                             isCrossing(bookingBlockBoundary)
                           )
            magicBits <- collectMagicBits(dag, bookingBlock, keyBlock)
            seed      = LeaderSequencer.seed(bookingBlock.messageHash.toByteArray, magicBits)
            childEra = Era(
              parentKeyBlockHash = era.keyBlockHash,
              keyBlockHash = keyBlock.messageHash,
              bookingBlockHash = bookingBlock.messageHash,
              startTick = conf.toTicks(end),
              endTick = conf.toTicks(conf.eraEnd(end)),
              bonds = bookingBlock.blockSummary.getHeader.getState.bonds,
              leaderSeed = ByteString.copyFrom(seed)
            )
            isNew <- EraStorage[F].addEra(childEra)
          } yield childEra.some.filter(_ => isNew)
        )
    }

    HighwayLog.liftF(maybeChildEra) flatMap {
      case Some(era) => HighwayLog.tell[F](HighwayEvent.CreatedEra(era))
      case None      => noop
    }
  }

  /** Find a block in the ancestry where the parent is before a time,
    * but the block is after the time. */
  private def findBlockCrossingBoundary(
      descendant: Message,
      isBoundary: (Instant, Instant) => Boolean
  ): F[Message] = {
    def loop(child: Message, childTime: Instant): F[Message] =
      // If we reached Genesis, for any reason, use it, there's nothing else further back.
      if (child.parentBlock.isEmpty) child.pure[F]
      else {
        dag.lookupUnsafe(child.parentBlock).flatMap { parent =>
          val parentTime = parent.roundInstant
          if (isBoundary(parentTime, childTime))
            child.pure[F]
          else
            loop(parent, parentTime)
        }
      }
    loop(descendant, descendant.roundInstant)
  }

  /** Pick a time during the round to send the omega message. */
  private def chooseOmegaTick(roundStart: Ticks, roundEnd: Ticks): Ticks = {
    val r = rng.nextDouble()
    val o = conf.omegaMessageTimeStart + r * (conf.omegaMessageTimeEnd - conf.omegaMessageTimeStart)
    val t = roundStart + o * (roundEnd - roundStart)
    Ticks(t.toLong)
  }

  /** Preliminary check before the block is executed. Invalid blocks can be dropped. */
  def validate(message: Message): EitherT[F, String, Unit] = {
    val ok = EitherT.rightT[F, String](())

    def checkF(errorMessage: String, errorCondition: F[Boolean]) = EitherT {
      errorCondition.ifM(errorMessage.asLeft[Unit].pure[F], ok.value)
    }
    def check(errorMessage: String, errorCondition: Boolean) =
      checkF(errorMessage, errorCondition.pure[F])

    val roundId      = Ticks(message.roundId)
    val isFromLeader = leaderFunction(roundId) == message.validatorId

    check(
      "The block is coming from a doppelganger.",
      maybeMessageProducer.map(_.validatorId).contains(message.validatorId)
    ) >> {
      message match {
        case b: Message.Block =>
          check(
            "The block is not coming from the leader of the round.",
            !isFromLeader
          ) >>
            checkF(
              "The leader has already sent a lambda message in this round.",
              // Not going to check this for ballots: two lambda-like ballots
              // can only be an equivocation, otherwise the 2nd one cites the first
              // and that means it's not lambda-like.
              hasOtherLambdaMessageInSameRound[F](dag, b, endTick)
            )
        case _ =>
          ok
      }
    }
  }

  /** Produce a starting agenda, depending on whether the validator is bonded or not. */
  def initAgenda: F[Agenda] =
    maybeMessageProducer.fold(Agenda.empty.pure[F]) { _ =>
      currentTick flatMap { tick =>
        isOverAt(tick).ifM(
          Agenda.empty.pure[F],
          roundBoundariesAt(tick) flatMap {
            case (from, to) =>
              val roundId = if (from >= tick) from else to
              Agenda(roundId -> StartRound(roundId)).pure[F]
          }
        )
      }
    }

  /** Handle a block or ballot coming from another validator. For example:
    * - if it's a lambda message, create a lambda response
    * - if it's a switch block, create a new era, unless it exists already.
    * Returns a list of events that happened during the persisting of changes.
    * This method is always called as a reaction to an incoming message,
    * so it doesn't return a future agenda of its own.
    */
  def handleMessage(message: Message): HWL[Unit] = {
    def illegal(msg: String) =
      MonadThrowable[HWL].raiseError[Unit](new IllegalStateException(msg))

    message match {
      // TODO (NODE-1116): Respond to lambda-ballots during voting.
      case _: Message.Ballot =>
        noop
      case b: Message.Block =>
        val roundId = Ticks(b.roundId)

        val response = maybeMessageProducer.fold(noop) { mp =>
          if (mp.validatorId == b.validatorId) {
            illegal("Shouldn't receive our own messages!")
          } else if (b.keyBlockHash != era.keyBlockHash) {
            illegal("Shouldn't receive messages from other eras!")
          } else if (leaderFunction(roundId) != b.validatorId) {
            // These blocks should fail validation and not be passed here.
            illegal("Shouldn't try to handle messages from non-leaders!")
          } else {
            // It's okay not to send a response to a message where we *did* participate
            // in the round it belongs to, but we moved on to a newer round.
            HighwayLog.liftF(currentTick.flatMap(roundBoundariesAt)) flatMap {
              case (from, _) if from == roundId =>
                createLambdaResponse(mp, b)
              case _ =>
                noop
            }
          }
        }

        response >> handleCriticalBlocks(b)
    }
  }

  /** Handle something that happens during a round:
    * - in rounds when we are leading, create a lambda message
    * - if it's a booking block, execute the auction
    * - if the main parent is a switch block, create a ballot instead
    * - sometime through the round, create an omega message
    * - if we're beyond the voting period after the end of the era, stop.
    *
    * Returns a list of events that took place, as well as its future agenda,
    * telling the caller (a supervisor) when it has to be scheduled again.
    */
  def handleAgenda(action: Agenda.Action): HWL[Agenda] =
    action match {
      case Agenda.StartRound(roundId) =>
        // Only create the agenda at the end, so if it takes too long to produce
        // a block we schedule the *next* round, possibly skipping a bunch.
        def agenda =
          for {
            now                           <- currentTick
            (currentRoundId, nextRoundId) <- roundBoundariesAt(now)
          } yield {
            val next = Agenda(nextRoundId -> Agenda.StartRound(nextRoundId))
            val omega = if (currentRoundId == roundId) {
              val omegaTick = chooseOmegaTick(roundId, nextRoundId)
              Agenda(omegaTick -> Agenda.CreateOmegaMessage(roundId))
            } else Agenda.empty

            omega ++ next
          }

        maybeMessageProducer.fold(noop) { mp =>
          if (leaderFunction(roundId) == mp.validatorId)
            createLambdaMessage(mp, roundId)
          else
            noop
        } >> HighwayLog.liftF(agenda)

      case Agenda.CreateOmegaMessage(roundId) =>
        maybeMessageProducer.fold(noop) {
          createOmegaMessage(_, roundId)
        } >> HighwayLog.liftF(Agenda.empty.pure[F])
    }

  /** Do any kind of special logic when we encounter a "critical" block in the protocol:
    * - booking blocks should execute the auction, but that should be in their
    *   post state hash as well, so we pass the flag to the message producer
    * - key blocks don't need special handling when they are made
    * - switch blocks might be the ones that grant the rewards,
    *   according to how many blocks were finalized on time during the era
    */
  private def handleCriticalBlocks(block: Message.Block): HWL[Unit] =
    if (block.parentBlock.isEmpty) noop
    else {
      for {
        parent <- HighwayLog.liftF[F, Message] {
                   dag.lookupUnsafe(block.parentBlock)
                 }
        parentTime = parent.roundInstant
        childTime  = block.roundInstant
        _          <- createEra(block).whenA(isSwitchBoundary(parentTime, childTime))
      } yield ()
    }
}

object EraRuntime {

  def fromGenesis[F[_]: Sync: Clock: DagStorage: EraStorage: ForkChoice](
      conf: HighwayConf,
      genesis: BlockSummary,
      maybeMessageProducer: Option[MessageProducer[F]],
      initRoundExponent: Int,
      isSynced: => F[Boolean],
      leaderSequencer: LeaderSequencer = LeaderSequencer
  ): F[EraRuntime[F]] = {
    val era = Era(
      keyBlockHash = genesis.blockHash,
      bookingBlockHash = genesis.blockHash,
      startTick = conf.toTicks(conf.genesisEraStart),
      endTick = conf.toTicks(conf.genesisEraEnd),
      bonds = genesis.getHeader.getState.bonds
    )
    fromEra[F](conf, era, maybeMessageProducer, initRoundExponent, isSynced, leaderSequencer)
  }

  def fromEra[F[_]: Sync: Clock: DagStorage: EraStorage: ForkChoice](
      conf: HighwayConf,
      era: Era,
      maybeMessageProducer: Option[MessageProducer[F]],
      initRoundExponent: Int,
      isSynced: => F[Boolean],
      leaderSequencer: LeaderSequencer = LeaderSequencer
  ): F[EraRuntime[F]] =
    for {
      leaderFunction   <- leaderSequencer[F](era)
      roundExponentRef <- Ref.of[F, Int](initRoundExponent)
      dag              <- DagStorage[F].getRepresentation
    } yield {
      new EraRuntime[F](
        conf,
        era,
        leaderFunction,
        roundExponentRef,
        // Whether the validator is bonded depends on the booking block. Only bonded validators
        // have to produce blocks and ballots in the era.
        maybeMessageProducer.filter { mp =>
          era.bonds.exists(b => b.validatorPublicKey == mp.validatorId)
        },
        isSynced,
        dag
      )
    }

  /** List of future actions to take. */
  type Agenda = Vector[Agenda.DelayedAction]

  object Agenda {
    sealed trait Action

    /** What action to take and when. */
    case class DelayedAction(tick: Ticks, action: Action)

    /** Handle one round:
      * - in rounds when we are leading, create a lambda message
      * - if it's a booking block, execute the auction
      * - if the main parent is a switch block, create a ballot instead
      * - if we're beyond the voting period after the end of the era, stop.
      */
    case class StartRound(roundId: Ticks) extends Action

    /** Create an Omega message, some time during the round */
    case class CreateOmegaMessage(roundId: Ticks) extends Action

    def apply(
        actions: (Ticks, Action)*
    ): Agenda =
      actions.map(DelayedAction.tupled).toVector

    val empty = apply()
  }

  /** Check that a parent timestamp is before, while the child is at or after a given boundary. */
  def isCrossing(boundary: Instant)(parent: Instant, child: Instant): Boolean =
    parent.isBefore(boundary) && !child.isBefore(boundary)

  /** Collect the magic bits between the booking block and the key block, */
  def collectMagicBits[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      bookingBlock: Message,
      keyBlock: Message
  ): F[Seq[Boolean]] =
    DagOperations
      .bfTraverseF(List(keyBlock)) { msg =>
        List(msg.parentBlock).filterNot(_.isEmpty).traverse(dag.lookupUnsafe)
      }
      .takeUntil(_.messageHash == bookingBlock.messageHash)
      .map(_.blockSummary.getHeader.magicBit)
      .toList
      .map(_.reverse)

  /** Check if a message cites another in the same round, which would mean it's not a lambda message.
    * The lambda message is created by the leader of the round; under normal cirumstances it comes
    * before the omega message, and therefore it only won't have a justification in the same round,
    * because all other validators are not leaders.
    */
  def hasJustificationInOwnRound[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      message: Message
  ): F[Boolean] =
    message.justifications.toList
      .findM[F] { j =>
        dag.lookupUnsafe(j.latestBlockHash).map { jmsg =>
          jmsg.keyBlockHash == message.keyBlockHash && jmsg.roundId == message.roundId
        }
      }
      .map(_.isDefined)

  /** Check that a ballot from a leader looks like a lambda message, that is,
    * it only cites messages from earlier rounds.
    */
  def isLambdaLikeBallot[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      ballot: Message.Ballot,
      eraEndTick: Ticks
  ): F[Boolean] =
    if (ballot.roundId < eraEndTick) false.pure[F]
    else hasJustificationInOwnRound(dag, ballot).map(!_)

  /** Given a lambda message from the leader of a round, check if the validator has sent
    * another lambda message already in the same round. Ignores equivocations, just the
    * checks the legal j-DAG.
    */
  def hasOtherLambdaMessageInSameRound[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      message: Message,
      eraEndTick: Ticks
  ): F[Boolean] =
    DagOperations
      .swimlaneV[F](
        message.validatorId,
        message,
        dag
      )
      .filter {
        // We know this one is a lambda message.
        _.messageHash != message.messageHash
      }
      .takeWhile { x =>
        // Only look at messages in this round, in this era.
        x.roundId == message.roundId && x.keyBlockHash == message.keyBlockHash
      }
      // Try to find a lambda message.
      .findF {
        case _: Message.Block =>
          // We established that this validator is the lead, so a block from them is a lambda.
          true.pure[F]
        case b: Message.Ballot if b.roundId >= eraEndTick =>
          // The era is over, so this is a voting only period message.
          // In the active period, it's easy to know that a ballot is either a lambda response or an omega.
          // In the voting only period, however, lambda messages are ballots too.
          // The only way to tell if a ballot might be a lambda may be a lambda message
          // is that it cites nothing from this round.
          hasJustificationInOwnRound(dag, b).map(!_)
        case _ =>
          false.pure[F]
      }
      .map(_.isDefined)
}
