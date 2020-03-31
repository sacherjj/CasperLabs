package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._
import cats.data.StateT
import com.google.protobuf.ByteString
import org.http4s.HttpRoutes
import doobie.util.transactor.Transactor
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.ValidatorIdentity
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.node.casper.consensus.Consensus
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.models.Message
import io.casperlabs.shared.Time
import io.casperlabs.storage.dag.{DagStorage, FinalityStorage}
import io.casperlabs.storage.era.EraStorage

object StatusInfo {

  case class Status(
      version: String,
      ok: Boolean,
      checklist: CheckList
  )

  case class Check[T](
      ok: Boolean,
      message: Option[String] = None,
      details: Option[T] = None
  )
  object Check {
    implicit class BoolStateOps[F[_]: Sync, T](s: StateT[F, Boolean, T]) {
      def withoutAccumulation: StateT[F, Boolean, T] = StateT { acc =>
        s.run(acc).map {
          case (_, x) => (acc, x)
        }
      }
    }

    /** Constructor that will catch errors in the check function as well as
      * aggregate the overall `ok` field across all the checks.
      */
    def apply[F[_]: Sync, T](f: F[Check[T]]): StateT[F, Boolean, Check[T]] = StateT { acc =>
      f.attempt.map {
        case Left(ex) => (false, Check[T](ok = false, message = ex.getMessage.some, details = None))
        case Right(x) => (acc && x.ok, x)
      }
    }

    case class BlockDetails(
        blockHash: String,
        timestamp: Long,
        jRank: Long
    )

    case class PeerDetails(
        count: Int
    )

    case class EraDetails(
        eras: List[String]
    )

    type Basic     = Check[Unit]
    type LastBlock = Check[BlockDetails]
    type Peers     = Check[PeerDetails]
    type Eras      = Check[EraDetails]
  }

  case class CheckList(
      database: Check.Basic,
      peers: Check.Peers,
      bootstrap: Check.Peers,
      initialSynchronization: Check.Basic,
      lastFinalizedBlock: Check.LastBlock,
      lastReceivedBlock: Check.Basic,
      lastCreatedBlock: Check.Basic,
      activeEras: Check.Eras,
      bondedEras: Check.Eras,
      genesisEra: Check.Eras
  )
  object CheckList {
    import Check._

    def apply[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: EraStorage: Consensus](
        conf: Configuration,
        genesis: Block,
        maybeValidatorId: Option[ByteString],
        getIsSynced: F[Boolean],
        readXa: Transactor[F]
    ): StateT[F, Boolean, CheckList] =
      for {
        database               <- database[F](readXa)
        peers                  <- peers[F](conf)
        bootstrap              <- bootstrap[F](conf, genesis)
        initialSynchronization <- initialSynchronization[F](getIsSynced)
        lastFinalizedBlock     <- lastFinalizedBlock[F](genesis)
        lastReceivedBlock      <- lastReceivedBlock[F](conf, maybeValidatorId)
        lastCreatedBlock       <- lastCreatedBlock[F](maybeValidatorId)
        activeEras             <- activeEras[F](conf)
        bondedEras             <- bondedEras[F](conf, maybeValidatorId)
        genesisEra             <- genesisEra[F](conf, genesis)
        checklist = CheckList(
          database = database,
          peers = peers,
          bootstrap = bootstrap,
          initialSynchronization = initialSynchronization,
          lastFinalizedBlock = lastFinalizedBlock,
          lastReceivedBlock = lastReceivedBlock,
          lastCreatedBlock = lastCreatedBlock,
          activeEras = activeEras,
          bondedEras = bondedEras,
          genesisEra = genesisEra
        )
      } yield checklist

    def database[F[_]: Sync](readXa: Transactor[F]) = Check {
      import doobie._
      import doobie.implicits._
      sql"""select 1""".query[Int].unique.transact(readXa).map { _ =>
        Check[Unit](ok = true)
      }
    }

    def peers[F[_]: Sync: NodeDiscovery](conf: Configuration) = Check {
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map { nodes =>
        Check(
          ok = conf.casper.standalone || nodes.nonEmpty,
          message = Some {
            if (conf.casper.standalone) s"Standalone mode, connected to ${nodes.length} peers."
            else s"Connected to ${nodes.length} recently alive peers."
          },
          details = PeerDetails(count = nodes.size).some
        )
      }
    }

    def bootstrap[F[_]: Sync: NodeDiscovery](conf: Configuration, genesis: Block) = Check {
      val bootstrapNodes = conf.server.bootstrap.map(_.withChainId(genesis.blockHash))
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map(_.toSet).map { nodes =>
        val connected = bootstrapNodes.filter(nodes)
        Check(
          ok = bootstrapNodes.isEmpty || connected.nonEmpty,
          message = Some {
            if (bootstrapNodes.isEmpty)
              s"Connected to ${connected.size} of the bootstrap nodes out of the ${bootstrapNodes.size} configured."
            else
              "Not bootstraps configured."
          },
          details = PeerDetails(count = nodes.size).some
        )
      }
    }

    def initialSynchronization[F[_]: Sync](getIsSynced: F[Boolean]) =
      Check {
        getIsSynced.map { synced =>
          Check[Unit](
            ok = synced,
            message = Option(
              if (synced) "Initial synchronization complete."
              else "Initial synchronization running."
            )
          )
        }
      }.withoutAccumulation

    private def isTooOld[F[_]: Sync: Time: Consensus](maybeMessage: Option[Message]): F[Boolean] =
      maybeMessage.fold(false.pure[F]) { message =>
        for {
          eras <- Consensus[F].activeEras
          now  <- Time[F].currentMillis
          tooOld = if (eras.isEmpty) false
          else {
            // Shorter alternative would be the booking duration from the ChainSpec.
            val eraDuration = eras.head.endTick - eras.head.startTick
            now - message.timestamp > eraDuration
          }
        } yield tooOld
      }

    def lastFinalizedBlock[F[_]: Sync: Time: FinalityStorage: DagStorage: Consensus](
        genesis: Block
    ) =
      Check {
        for {
          lfbHash  <- FinalityStorage[F].getLastFinalizedBlock
          dag      <- DagStorage[F].getRepresentation
          lfb      <- dag.lookupUnsafe(lfbHash)
          isTooOld <- isTooOld(lfb.some)

          maybeError = if (lfbHash == genesis.blockHash)
            "The last finalized block is the Genesis.".some
          else if (isTooOld) "Last block was finalized a too long ago.".some
          else none

        } yield Check(
          ok = maybeError.isEmpty,
          message = maybeError,
          details = BlockDetails(
            blockHash = hex(lfb.messageHash),
            timestamp = lfb.timestamp,
            jRank = lfb.jRank
          ).some
        )
      }

    // Only returning basic info so as not to reveal the validator identity by process of elimination,
    // i.e. which validator's block is it that this node _never_ says it received.
    def lastReceivedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        conf: Configuration,
        maybeValidatorId: Option[ByteString]
    ) = Check {
      for {
        dag      <- DagStorage[F].getRepresentation
        tips     <- dag.latestGlobal
        messages <- tips.latestMessages
        received = messages.values.flatten.filter { m =>
          maybeValidatorId.fold(true)(_ != m.validatorId)
        }
        latest   = if (received.nonEmpty) received.maxBy(_.timestamp).some else none
        isTooOld <- isTooOld(latest)

        maybeError = if (conf.casper.standalone) none[String]
        else if (isTooOld) "Last block was received too long ago.".some
        else if (latest.isEmpty) "Haven't received any blocks yet".some
        else none

        maybeAlone = if (conf.casper.standalone) "Running in standalone mode.".some else none

      } yield Check[Unit](
        ok = maybeError.isEmpty,
        message = maybeError orElse maybeAlone
      )
    }

    // Returning basic info so as not to reveal the validator identity through the block ID.
    def lastCreatedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        maybeValidatorId: Option[ByteString]
    ) = Check {
      for {
        created <- maybeValidatorId.fold(Set.empty[Message].pure[F]) { id =>
                    for {
                      dag      <- DagStorage[F].getRepresentation
                      tips     <- dag.latestGlobal
                      messages <- tips.latestMessage(id)
                    } yield messages
                  }
        latest   = if (created.nonEmpty) created.maxBy(_.timestamp).some else none
        isTooOld <- isTooOld(latest)
      } yield Check[Unit](
        ok = maybeValidatorId.isEmpty || created.nonEmpty && !isTooOld,
        message =
          if (maybeValidatorId.isEmpty) "Running in read-only mode.".some
          else if (latest.isEmpty) "Haven't created any blocks yet.".some
          else if (isTooOld) "The last created block was too long ago.".some
          else none
      )
    }

    def activeEras[F[_]: Sync: Time: Consensus](conf: Configuration) = Check {
      if (!conf.highway.enabled)
        Check(ok = true, message = "Not in highway mode.".some, details = none[EraDetails]).pure[F]
      else
        for {
          active       <- Consensus[F].activeEras
          now          <- Time[F].currentMillis
          (past, curr) = active.partition(era => era.startTick < now)
          maybeError = if (active.isEmpty) "There are no active eras.".some
          else if (curr.size > 1) "There are more than 1 current eras.".some
          else none
        } yield {
          Check(
            ok = maybeError.isEmpty,
            message = maybeError,
            details = EraDetails(eras = active.map(_.keyBlockHash).toList.map(hex)).some
          )
        }
    }

    def genesisEra[F[_]: Sync: Time: EraStorage](conf: Configuration, genesis: Block) = Check {
      if (!conf.highway.enabled)
        Check(ok = true, message = "Not in highway mode.".some, details = none[EraDetails]).pure[F]
      else
        for {
          now        <- Time[F].currentMillis
          genesisEra <- EraStorage[F].getEraUnsafe(genesis.blockHash)
        } yield {
          Check(
            ok = true,
            message =
              if (genesisEra.startTick > now) "Genesis era hasn't started yet.".some else none,
            details = EraDetails(eras = List(hex(genesisEra.keyBlockHash))).some
          )
        }
    }

    def bondedEras[F[_]: Sync: Consensus](
        conf: Configuration,
        maybeValidatorId: Option[ByteString]
    ) = Check {
      maybeValidatorId match {
        case _ if !conf.highway.enabled =>
          Check(ok = true, message = "Not in highway mode.".some, details = none[EraDetails])
            .pure[F]
        case None =>
          Check(ok = true, message = "Running in read-only mode".some, details = none[EraDetails])
            .pure[F]
        case Some(id) =>
          for {
            active <- Consensus[F].activeEras
            bonded = active
              .filter(era => era.bonds.exists(_.validatorPublicKey == id))
              .map(_.keyBlockHash)
          } yield {
            Check(
              ok = bonded.nonEmpty,
              message = Option("Not bonded in any active era.").filter(_ => bonded.isEmpty),
              details = EraDetails(eras = bonded.toList.map(hex)).some
            )
          }

      }
    }

    private def hex(h: ByteString) = Base16.encode(h.toByteArray)
  }

  def status[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: EraStorage: Consensus](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ByteString],
      getIsSynced: F[Boolean],
      readXa: Transactor[F]
  ): F[Status] =
    for {
      version <- Sync[F].delay(VersionInfo.get)
      (ok, checklist) <- CheckList[F](conf, genesis, maybeValidatorId, getIsSynced, readXa)
                          .run(true)
    } yield Status(version, ok, checklist)

  def service[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: EraStorage: Consensus](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ValidatorIdentity],
      getIsSynced: F[Boolean],
      readXa: Transactor[F]
  ): HttpRoutes[F] = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityEncoder._

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    val maybeValidatorKey = maybeValidatorId.map(id => ByteString.copyFrom(id.publicKey))

    HttpRoutes.of[F] {
      // Could return a different HTTP status code, but it really depends on what we want from this.
      // An 50x would mean the service is kaput, which may be too harsh.
      case GET -> Root =>
        Ok(
          status(conf, genesis, maybeValidatorKey, getIsSynced, readXa)
            .map(_.asJson)
        )
    }
  }
}
