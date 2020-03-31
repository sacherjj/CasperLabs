package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._
import cats.data.StateT
import com.google.protobuf.ByteString
import org.http4s.HttpRoutes
import java.time.Instant
import doobie.util.transactor.Transactor
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.{Block, Era}
import io.casperlabs.casper.ValidatorIdentity
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.node.casper.consensus.Consensus
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.models.Message
import io.casperlabs.shared.{ByteStringPrettyPrinter, Time}
import io.casperlabs.storage.dag.{DagStorage, FinalityStorage}
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.ipc.ChainSpec

object StatusInfo {
  import ByteStringPrettyPrinter.byteStringShow

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
        timestamp: String,
        jRank: Long
    )
    object BlockDetails {
      def apply(message: Message): BlockDetails =
        BlockDetails(
          blockHash = message.messageHash.show,
          timestamp = Instant.ofEpochMilli(message.timestamp).toString,
          jRank = message.jRank
        )
    }

    case class PeerDetails(
        count: Int
    )

    case class EraDetails(
        keyBlockHash: String,
        startTimestamp: String,
        endTimestamp: String
    )

    case class ErasDetails(
        eras: List[EraDetails]
    )
    object ErasDetails {
      def apply(eras: Set[Era]): ErasDetails =
        ErasDetails(
          eras.toList.map(
            era =>
              EraDetails(
                keyBlockHash = era.keyBlockHash.show,
                startTimestamp = Instant.ofEpochMilli(era.startTick).toString,
                endTimestamp = Instant.ofEpochMilli(era.endTick).toString
              )
          )
        )
    }

    type Basic     = Check[Unit]
    type LastBlock = Check[BlockDetails]
    type Peers     = Check[PeerDetails]
    type Eras      = Check[ErasDetails]
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
        chainSpec: ChainSpec,
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
        lastFinalizedBlock     <- lastFinalizedBlock[F](chainSpec, genesis)
        lastReceivedBlock      <- lastReceivedBlock[F](conf, chainSpec, maybeValidatorId)
        lastCreatedBlock       <- lastCreatedBlock[F](chainSpec, maybeValidatorId)
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
            if (bootstrapNodes.nonEmpty)
              s"Connected to ${connected.size} of the bootstrap nodes out of the ${bootstrapNodes.size} configured."
            else
              "No bootstraps configured."
          },
          details = PeerDetails(count = connected.size).some
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

    private def isTooOld[F[_]: Sync: Time](
        chainSpec: ChainSpec,
        maybeMessage: Option[Message]
    ): F[Boolean] =
      maybeMessage.fold(false.pure[F]) { message =>
        for {
          now <- Time[F].currentMillis
          // Using the entropy duration, which is a timespan during which there must be some blocks.
          entropyDurationMillis = chainSpec.getGenesis.getHighwayConfig.entropyDurationMillis
          tooOld = if (entropyDurationMillis == 0) false
          else now - message.timestamp > entropyDurationMillis
        } yield tooOld
      }

    private def findLatest(messages: Set[Message]): Option[Message] =
      if (messages.isEmpty) none else messages.maxBy(_.timestamp).some

    def lastFinalizedBlock[F[_]: Sync: Time: FinalityStorage: DagStorage: Consensus](
        chainSpec: ChainSpec,
        genesis: Block
    ) =
      Check {
        for {
          lfbHash  <- FinalityStorage[F].getLastFinalizedBlock
          dag      <- DagStorage[F].getRepresentation
          lfb      <- dag.lookupUnsafe(lfbHash)
          isTooOld <- isTooOld(chainSpec, lfb.some)
        } yield Check(
          ok = !isTooOld,
          message = Some {
            if (isTooOld) "Last block was finalized too long ago."
            else if (lfbHash == genesis.blockHash) "The last finalized block is the Genesis."
            else "The last finalized block was moved not too long ago."
          },
          details = BlockDetails(lfb).some
        )
      }

    // Only returning basic info so as not to reveal the validator identity by process of elimination,
    // i.e. which validator's block is it that this node _never_ says it received.
    def lastReceivedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        conf: Configuration,
        chainSpec: ChainSpec,
        maybeValidatorId: Option[ByteString]
    ) = Check {
      for {
        dag      <- DagStorage[F].getRepresentation
        tips     <- dag.latestGlobal
        messages <- tips.latestMessages
        received = messages.values.flatten.toSet.filter { m =>
          maybeValidatorId.fold(true)(_ != m.validatorId)
        }
        latest   = findLatest(received)
        isTooOld <- isTooOld(chainSpec, latest)

      } yield Check[Unit](
        ok = !isTooOld,
        message = Some {
          if (isTooOld) "Last block was received too long ago."
          else if (latest.nonEmpty) "Received a block not too long ago."
          else if (conf.casper.standalone) "Running in standalone mode."
          else "Haven't received a block yet."
        }
      )
    }

    // Returning basic info so as not to reveal the validator identity through the block ID.
    def lastCreatedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        chainSpec: ChainSpec,
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
        latest   = findLatest(created)
        isTooOld <- isTooOld(chainSpec, latest)
      } yield Check[Unit](
        ok = !isTooOld,
        message = Some {
          if (isTooOld) "The last created block was too long ago."
          else if (maybeValidatorId.isEmpty) "Running in read-only mode."
          else if (latest.isEmpty) "Haven't created any blocks yet."
          else "Created a block not too long ago."
        }
      )
    }

    def activeEras[F[_]: Sync: Time: Consensus](conf: Configuration) = Check {
      if (!conf.highway.enabled)
        Check[ErasDetails](ok = true, message = "Not in highway mode.".some).pure[F]
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
            details = ErasDetails(active).some
          )
        }
    }

    def genesisEra[F[_]: Sync: Time: EraStorage](conf: Configuration, genesis: Block) = Check {
      if (!conf.highway.enabled)
        Check[ErasDetails](ok = true, message = "Not in highway mode.".some).pure[F]
      else
        for {
          now        <- Time[F].currentMillis
          genesisEra <- EraStorage[F].getEraUnsafe(genesis.blockHash)
        } yield {
          Check(
            ok = true,
            message =
              if (genesisEra.startTick > now) "Genesis era hasn't started yet.".some else none,
            details = ErasDetails(Set(genesisEra)).some
          )
        }
    }

    def bondedEras[F[_]: Sync: Consensus](
        conf: Configuration,
        maybeValidatorId: Option[ByteString]
    ) = Check {
      maybeValidatorId match {
        case _ if !conf.highway.enabled =>
          Check[ErasDetails](ok = true, message = "Not in highway mode.".some)
            .pure[F]
        case None =>
          Check[ErasDetails](ok = true, message = "Running in read-only mode".some)
            .pure[F]
        case Some(id) =>
          for {
            active <- Consensus[F].activeEras
            bonded = active.filter(era => era.bonds.exists(_.validatorPublicKey == id))
          } yield {
            Check(
              ok = bonded.nonEmpty,
              message = Some {
                if (bonded.isEmpty) "Not bonded in any active era."
                else s"Bonded in ${bonded.size} active eras."
              },
              details = ErasDetails(bonded).some
            )
          }

      }
    }

  }

  class Service[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: EraStorage: Consensus](
      conf: Configuration,
      chainSpec: ChainSpec,
      genesis: Block,
      maybeValidatorId: Option[ByteString],
      getIsSynced: F[Boolean],
      readXa: Transactor[F]
  ) {
    def getStatus: F[Status] =
      for {
        version <- Sync[F].delay(VersionInfo.get)
        (ok, checklist) <- CheckList[F](
                            conf,
                            chainSpec,
                            genesis,
                            maybeValidatorId,
                            getIsSynced,
                            readXa
                          ).run(true)
      } yield Status(version, ok, checklist)
  }

  def service[F[_]: Sync](
      service: Service[F]
  ): HttpRoutes[F] = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityEncoder._

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      // Could return a different HTTP status code, but it really depends on what we want from this.
      // An 50x would mean the service is kaput, which may be too harsh.
      case GET -> Root => Ok(service.getStatus.map(_.asJson))
    }
  }
}
