package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._
import cats.data.StateT
import com.google.protobuf.ByteString
import doobie.util.transactor.Transactor
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Block
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.node.configuration.Configuration
import org.http4s.HttpRoutes
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.models.Message
import io.casperlabs.casper.ValidatorIdentity

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
    case class Checked(val ok: Boolean, val message: Option[String] = None) extends Check
    object Checked {

      /** Constructor that will catch errors in the check function as well as
        * aggregate the overall `ok` field across all the checks.
        */
      def apply[F[_]: Sync](f: F[Checked]): StateT[F, Boolean, Checked] = StateT { acc =>
        f.attempt.map {
          case Left(ex) => (false, Checked(ok = false, message = ex.getMessage.some))
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
              // All subclasses need error handling. Alternatively we could use `Either` with custom JSON encoding.
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
  }

  case class CheckList(
      database: Check.Checked,
      peers: Check.Checked,
      bootstrap: Check.Checked,
      lastReceivedBlock: Check.LastBlock,
      lastCreatedBlock: Check.LastBlock
  )
  object CheckList {
    import Check._

    def apply[F[_]: Sync: NodeDiscovery: DagStorage](
        conf: Configuration,
        genesis: Block,
        maybeValidatorId: Option[ByteString],
        readXa: Transactor[F]
    ): StateT[F, Boolean, CheckList] =
      for {
        database          <- database[F](readXa)
        peers             <- peers[F](conf)
        bootstrap         <- bootstrap[F](conf, genesis)
        lastReceivedBlock <- lastReceivedBlock[F](conf, maybeValidatorId)
        lastCreatedBlock  <- lastCreatedBlock[F](maybeValidatorId)
        checklist = CheckList(
          database = database,
          peers = peers,
          bootstrap = bootstrap,
          lastReceivedBlock = lastReceivedBlock,
          lastCreatedBlock = lastCreatedBlock
        )
      } yield checklist

    def database[F[_]: Sync](readXa: Transactor[F]) = Checked {
      import doobie._
      import doobie.implicits._
      sql"""select 1""".query[Int].unique.transact(readXa).map { _ =>
        Checked(ok = true)
      }
    }

    def peers[F[_]: Sync: NodeDiscovery](conf: Configuration) = Checked {
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map { nodes =>
        Checked(
          ok = conf.casper.standalone || nodes.nonEmpty,
          message = s"${nodes.length} recently alive peers.".some
        )
      }
    }

    def bootstrap[F[_]: Sync: NodeDiscovery](conf: Configuration, genesis: Block) = Checked {
      val bootstrapNodes = conf.server.bootstrap.map(_.withChainId(genesis.blockHash))
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map(_.toSet).map { nodes =>
        val connected = bootstrapNodes.filter(nodes)
        Checked(
          ok = bootstrapNodes.isEmpty || connected.nonEmpty,
          message = s"Connected to ${connected.size} of the bootstrap nodes.".some
        )
      }
    }

    def lastReceivedBlock[F[_]: Sync: DagStorage](
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
      } yield LastBlock(
        ok = conf.casper.standalone || latest.nonEmpty,
        message = latest.fold("Haven't received any blocks yet.".some)(_ => none),
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
        message = latest.fold("Haven't created any blocks yet.".some)(_ => none),
        blockHash = latest.map(m => Base16.encode(m.messageHash.toByteArray)),
        timestamp = latest.map(_.timestamp),
        jRank = latest.map(_.jRank)
      )
    }
  }

  def status[F[_]: Sync: NodeDiscovery: DagStorage](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ByteString],
      readXa: Transactor[F]
  ): F[Status] =
    for {
      version         <- Sync[F].delay(VersionInfo.get)
      (ok, checklist) <- CheckList[F](conf, genesis, maybeValidatorId, readXa).run(true)
    } yield Status(version, ok, checklist)

  def service[F[_]: Sync: NodeDiscovery: DagStorage](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ValidatorIdentity],
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
          status(conf, genesis, maybeValidatorKey, readXa)
            .map(_.asJson)
        )
    }
  }
}
