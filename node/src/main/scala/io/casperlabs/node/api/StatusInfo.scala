package io.casperlabs.node.api

import cats.effect.Sync
import cats.effect.concurrent.Ref
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
import io.casperlabs.storage.dag.DagStorage

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
  }

  case class CheckList(
      database: Check.Basic,
      peers: Check.Basic,
      bootstrap: Check.Basic,
      initialSynchronization: Check.Basic,
      lastReceivedBlock: Check.LastBlock,
      lastCreatedBlock: Check.LastBlock,
      activeEras: Check.Eras,
      bondedEras: Check.Eras
  )
  object CheckList {
    import Check._

    def apply[F[_]: Sync: NodeDiscovery: DagStorage: Consensus](
        conf: Configuration,
        genesis: Block,
        maybeValidatorId: Option[ByteString],
        isSyncedRef: Ref[F, Boolean],
        readXa: Transactor[F]
    ): StateT[F, Boolean, CheckList] =
      for {
        database               <- database[F](readXa)
        peers                  <- peers[F](conf)
        bootstrap              <- bootstrap[F](conf, genesis)
        initialSynchronization <- initialSynchronization[F](isSyncedRef)
        lastReceivedBlock      <- lastReceivedBlock[F](conf, maybeValidatorId)
        lastCreatedBlock       <- lastCreatedBlock[F](maybeValidatorId)
        activeEras             <- activeEras[F](conf)
        bondedEras             <- bondedEras[F](conf, maybeValidatorId)
        checklist = CheckList(
          database = database,
          peers = peers,
          bootstrap = bootstrap,
          initialSynchronization = initialSynchronization,
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

    def peers[F[_]: Sync: NodeDiscovery](conf: Configuration) = Basic {
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map { nodes =>
        Basic(
          ok = conf.casper.standalone || nodes.nonEmpty,
          message = Some {
            val mode = if (conf.casper.standalone) "Running in standalone mode. " else ""
            mode + s"Connected to ${nodes.length} recently alive peers."
          }
        )
      }
    }

    def bootstrap[F[_]: Sync: NodeDiscovery](conf: Configuration, genesis: Block) = Basic {
      val bootstrapNodes = conf.server.bootstrap.map(_.withChainId(genesis.blockHash))
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map(_.toSet).map { nodes =>
        val connected = bootstrapNodes.filter(nodes)
        Basic(
          ok = bootstrapNodes.isEmpty || connected.nonEmpty,
          message = Some {
            if (bootstrapNodes.isEmpty)
              s"Connected to ${connected.size} of the bootstrap nodes."
            else
              "Not bootstraps configured."
          }
        )
      }
    }

    def initialSynchronization[F[_]: Sync](isSyncedRef: Ref[F, Boolean]) =
      Basic {
        isSyncedRef.get.map { synced =>
          Basic(
            ok = synced,
            message = Option(
              if (synced) "Initial synchronization complete."
              else "Initial synchronization running."
            )
          )
        }
      }.withoutAccumulation

    def lastReceivedBlock[F[_]: Sync: DagStorage: Consensus](
        conf: Configuration,
        maybeValidatorId: Option[ByteString]
    ) = LastBlock {
      for {
        dag      <- DagStorage[F].getRepresentation
        tips     <- dag.latestGlobal
        messages <- tips.latestMessages
        received = messages.values.flatten.filter { m =>
          maybeValidatorId.fold(true)(_ != m.validatorId)
        }
        latest = if (received.nonEmpty) received.maxBy(_.timestamp).some else none
        eras   <- Consensus[F].activeEras

        isTooOld = if (eras.isEmpty) false
        else {
          // Shorter alternative would be the booking duration from the ChainSpec.
          val eraDuration = eras.head.endTick - eras.head.startTick
          val now         = System.currentTimeMillis
          latest.fold(false)(now - _.timestamp > eraDuration)
        }

        maybeError = if (conf.casper.standalone) none[String]
        else if (isTooOld) "Last block was received too long ago.".some
        else if (latest.isEmpty) "Haven't received any blocks yet".some
        else none

        maybeAlone = if (conf.casper.standalone) "Running in standalone mode.".some else none

      } yield LastBlock(
        ok = maybeError.isEmpty,
        message = maybeError orElse maybeAlone,
        blockHash = latest.map(m => Base16.encode(m.messageHash.toByteArray)),
        timestamp = latest.map(_.timestamp),
        jRank = latest.map(_.jRank)
      )
    }

    def lastCreatedBlock[F[_]: Sync: DagStorage](
        maybeValidatorId: Option[ByteString]
    ) = LastBlock {
      for {
        created <- maybeValidatorId.fold(Set.empty[Message].pure[F]) { id =>
                    for {
                      dag      <- DagStorage[F].getRepresentation
                      tips     <- dag.latestGlobal
                      messages <- tips.latestMessage(id)
                    } yield messages
                  }
        latest = if (created.nonEmpty) created.maxBy(_.timestamp).some else none
      } yield LastBlock(
        ok = maybeValidatorId.isEmpty || created.nonEmpty,
        message =
          if (maybeValidatorId.isEmpty) "Running in read-only mode.".some
          else if (latest.isEmpty) "Haven't created any blocks yet.".some
          else none,
        blockHash = latest.map(m => hex(m.messageHash)),
        timestamp = latest.map(_.timestamp),
        jRank = latest.map(_.jRank)
      )
    }

    def activeEras[F[_]: Sync: Consensus](conf: Configuration) = Eras {
      if (!conf.highway.enabled)
        Eras(ok = true, message = "Not in highway mode.".some, eras = Nil).pure[F]
      else
        for {
          active       <- Consensus[F].activeEras
          now          = System.currentTimeMillis
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

  def status[F[_]: Sync: NodeDiscovery: DagStorage: Consensus](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ByteString],
      isSyncedRef: Ref[F, Boolean],
      readXa: Transactor[F]
  ): F[Status] =
    for {
      version <- Sync[F].delay(VersionInfo.get)
      (ok, checklist) <- CheckList[F](conf, genesis, maybeValidatorId, isSyncedRef, readXa)
                          .run(true)
    } yield Status(version, ok, checklist)

  def service[F[_]: Sync: NodeDiscovery: DagStorage: Consensus](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ValidatorIdentity],
      isSyncedRef: Ref[F, Boolean],
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
          status(conf, genesis, maybeValidatorKey, isSyncedRef, readXa)
            .map(_.asJson)
        )
    }
  }
}
