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

object StatusInfo {

  case class Status(
      version: String,
      ok: Boolean,
      checklist: CheckList
  )

  trait Check {
    val ok: Boolean
    val message: Option[String]
  }
  object Check {
    implicit class BoolStateOps[F[_]: Sync, T](s: StateT[F, Boolean, T]) {
      def withoutAccumulation: StateT[F, Boolean, T] = StateT { acc =>
        s.run(acc).map {
          case (_, x) => (acc, x)
        }
      }
    }

    case class Basic(val ok: Boolean, val message: Option[String] = None) extends Check
    object Basic {

      /** Constructor that will catch errors in the check function as well as
        * aggregate the overall `ok` field across all the checks.
        */
      def apply[F[_]: Sync](f: F[Basic]): StateT[F, Boolean, Basic] = StateT { acc =>
        f.attempt.map {
          // All subclasses need error handling. Alternatively we could use `Either` with custom JSON encoding.
          case Left(ex) => (false, Basic(ok = false, message = ex.getMessage.some))
          case Right(x) => (acc && x.ok, x)
        }
      }
    }

    case class LastBlock(
        ok: Boolean,
        message: Option[String] = None,
        blockHash: Option[String],
        timestamp: Option[Long],
        jRank: Option[Long]
    ) extends Check
    object LastBlock {
      def apply[F[_]: Sync](f: F[LastBlock]): StateT[F, Boolean, LastBlock] = StateT { acc =>
        f.attempt.map {
          case Left(ex) =>
            (
              false,
              LastBlock(
                ok = false,
                message = ex.getMessage.some,
                blockHash = None,
                timestamp = None,
                jRank = None
              )
            )
          case Right(x) => (acc && x.ok, x)
        }
      }
    }

    case class Eras(val ok: Boolean, val message: Option[String] = None, eras: List[String])
        extends Check
    object Eras {
      def apply[F[_]: Sync](f: F[Eras]): StateT[F, Boolean, Eras] = StateT { acc =>
        f.attempt.map {
          case Left(ex) => (false, Eras(ok = false, message = ex.getMessage.some, eras = Nil))
          case Right(x) => (acc && x.ok, x)
        }
      }
    }

    case class Nodes(val ok: Boolean, val message: Option[String] = None, count: Int) extends Check
    object Nodes {
      def apply[F[_]: Sync](f: F[Nodes]): StateT[F, Boolean, Nodes] = StateT { acc =>
        f.attempt.map {
          case Left(ex) => (false, Nodes(ok = false, message = ex.getMessage.some, count = 0))
          case Right(x) => (acc && x.ok, x)
        }
      }
    }
  }

  case class CheckList(
      database: Check.Basic,
      peers: Check.Nodes,
      bootstrap: Check.Nodes,
      initialSynchronization: Check.Basic,
      lastFinalizedBlock: Check.LastBlock,
      lastReceivedBlock: Check.Basic,
      lastCreatedBlock: Check.Basic,
      activeEras: Check.Eras,
      bondedEras: Check.Eras
  )
  object CheckList {
    import Check._

    def apply[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: Consensus](
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
        checklist = CheckList(
          database = database,
          peers = peers,
          bootstrap = bootstrap,
          initialSynchronization = initialSynchronization,
          lastFinalizedBlock = lastFinalizedBlock,
          lastReceivedBlock = lastReceivedBlock,
          lastCreatedBlock = lastCreatedBlock,
          activeEras = activeEras,
          bondedEras = bondedEras
        )
      } yield checklist

    def database[F[_]: Sync](readXa: Transactor[F]) = Basic {
      import doobie._
      import doobie.implicits._
      sql"""select 1""".query[Int].unique.transact(readXa).map { _ =>
        Basic(ok = true)
      }
    }

    def peers[F[_]: Sync: NodeDiscovery](conf: Configuration) = Nodes {
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map { nodes =>
        Nodes(
          ok = conf.casper.standalone || nodes.nonEmpty,
          message = Some {
            val mode = if (conf.casper.standalone) "Running in standalone mode. " else ""
            mode + s"Connected to ${nodes.length} recently alive peers."
          },
          count = nodes.size
        )
      }
    }

    def bootstrap[F[_]: Sync: NodeDiscovery](conf: Configuration, genesis: Block) = Nodes {
      val bootstrapNodes = conf.server.bootstrap.map(_.withChainId(genesis.blockHash))
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map(_.toSet).map { nodes =>
        val connected = bootstrapNodes.filter(nodes)
        Nodes(
          ok = bootstrapNodes.isEmpty || connected.nonEmpty,
          message = Some {
            if (bootstrapNodes.isEmpty)
              s"Connected to ${connected.size} of the bootstrap nodes out of the ${bootstrapNodes.size} configured."
            else
              "Not bootstraps configured."
          },
          count = nodes.size
        )
      }
    }

    def initialSynchronization[F[_]: Sync](getIsSynced: F[Boolean]) =
      Basic {
        getIsSynced.map { synced =>
          Basic(
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
      LastBlock {
        for {
          lfbHash  <- FinalityStorage[F].getLastFinalizedBlock
          dag      <- DagStorage[F].getRepresentation
          lfb      <- dag.lookupUnsafe(lfbHash)
          isTooOld <- isTooOld(lfb.some)

          maybeError = if (lfbHash == genesis.blockHash)
            "The last finalized block is the Genesis.".some
          else if (isTooOld) "Last block was finalized a too long ago.".some
          else none

        } yield LastBlock(
          ok = maybeError.isEmpty,
          message = maybeError,
          blockHash = hex(lfb.messageHash).some,
          timestamp = lfb.timestamp.some,
          jRank = lfb.jRank.some
        )
      }

    // Only returning basic info so as not to reveal the validator identity by process of elimination,
    // i.e. which validator's block is it that this node _never_ says it received.
    def lastReceivedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        conf: Configuration,
        maybeValidatorId: Option[ByteString]
    ) = Basic {
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

      } yield Basic(
        ok = maybeError.isEmpty,
        message = maybeError orElse maybeAlone
      )
    }

    // Returning basic info so as not to reveal the validator identity through the block ID.
    def lastCreatedBlock[F[_]: Sync: Time: DagStorage: Consensus](
        maybeValidatorId: Option[ByteString]
    ) = Basic {
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
      } yield Basic(
        ok = maybeValidatorId.isEmpty || created.nonEmpty && !isTooOld,
        message =
          if (maybeValidatorId.isEmpty) "Running in read-only mode.".some
          else if (latest.isEmpty) "Haven't created any blocks yet.".some
          else if (isTooOld) "The last created block was too long ago.".some
          else none
      )
    }

    def activeEras[F[_]: Sync: Time: Consensus](conf: Configuration) = Eras {
      if (!conf.highway.enabled)
        Eras(ok = true, message = "Not in highway mode.".some, eras = Nil).pure[F]
      else
        for {
          active       <- Consensus[F].activeEras
          now          <- Time[F].currentMillis
          (past, curr) = active.partition(era => era.startTick < now)
          maybeError = if (active.isEmpty) "There are no active eras.".some
          else if (curr.size > 1) "There are more than 1 current eras.".some
          else none
        } yield {
          Eras(
            ok = maybeError.isEmpty,
            message = maybeError,
            eras = active.map(_.keyBlockHash).toList.map(hex)
          )
        }
    }

    def bondedEras[F[_]: Sync: Consensus](
        conf: Configuration,
        maybeValidatorId: Option[ByteString]
    ) = Eras {
      if (!conf.highway.enabled)
        Eras(ok = true, message = "Not in highway mode.".some, eras = Nil).pure[F]
      else
        maybeValidatorId match {
          case None =>
            Eras(ok = true, message = "Running in read-only mode".some, eras = Nil).pure[F]
          case Some(id) =>
            for {
              active <- Consensus[F].activeEras
              bonded = active
                .filter(era => era.bonds.exists(_.validatorPublicKey == id))
                .map(_.keyBlockHash)
            } yield {
              Eras(
                ok = bonded.nonEmpty,
                message = Option("Not bonded in any active era.").filter(_ => bonded.isEmpty),
                eras = bonded.toList.map(hex)
              )
            }

        }
    }

    private def hex(h: ByteString) = Base16.encode(h.toByteArray)
  }

  def status[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: Consensus](
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

  def service[F[_]: Sync: Time: NodeDiscovery: DagStorage: FinalityStorage: Consensus](
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
