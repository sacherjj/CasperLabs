package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._
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
    case class LastBlock(
        ok: Boolean,
        message: Option[String] = None,
        blockHash: Option[String],
        timestamp: Option[Long]
    ) extends Check

    def peers[F[_]: Sync: NodeDiscovery](conf: Configuration) =
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map { nodes =>
        Checked(
          ok = conf.casper.standalone || nodes.nonEmpty,
          message = s"${nodes.length} recently alive peers.".some
        )
      }

    def bootstrap[F[_]: Sync: NodeDiscovery](conf: Configuration, genesis: Block) = {
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
        maybeValidatorId: Option[ValidatorIdentity]
    ): F[LastBlock] =
      for {
        dag      <- DagStorage[F].getRepresentation
        tips     <- dag.latestGlobal
        messages <- tips.latestMessages
        latests = messages.values.flatten.filter { m =>
          maybeValidatorId.fold(true)(id => id.publicKey != m.validatorId.toByteArray)
        }
        latest = if (latests.nonEmpty) latests.maxBy(_.timestamp).some else none
      } yield LastBlock(
        ok = conf.casper.standalone || latest.nonEmpty,
        message = latest.fold("Haven't received any blocks yet.".some)(_ => none),
        blockHash = latest.map(m => Base16.encode(m.messageHash.toByteArray)),
        timestamp = latest.map(_.timestamp)
      )

  }

  case class CheckList(
      peers: Check.Checked,
      bootstrap: Check.Checked,
      lastReceivedBlock: Check.LastBlock
  ) {
    // I thought about putting everything in a `List[_ <: Check]` and having a custom Json Encoder to
    // show them as an object, or producing a Json object first and parsing the flags, or using Shapeless.
    // This pedestrian version is the most straight forward, but in the future I'd go with Shapeless.
    def ok = List(peers, bootstrap, lastReceivedBlock).forall(_.ok)
  }

  def status[F[_]: Sync: NodeDiscovery: DagStorage](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ValidatorIdentity]
  ): F[Status] =
    for {
      version           <- Sync[F].delay(VersionInfo.get)
      peers             <- Check.peers[F](conf)
      bootstrap         <- Check.bootstrap[F](conf, genesis)
      lastReceivedBlock <- Check.lastReceivedBlock[F](conf, maybeValidatorId)
      checklist = CheckList(
        peers = peers,
        bootstrap = bootstrap,
        lastReceivedBlock = lastReceivedBlock
      )
    } yield Status(version, checklist.ok, checklist)

  def service[F[_]: Sync: NodeDiscovery: DagStorage](
      conf: Configuration,
      genesis: Block,
      maybeValidatorId: Option[ValidatorIdentity]
  ): HttpRoutes[F] = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityEncoder._

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      // Could return a different HTTP status code, but it really depends on what we want from this.
      // An 50x would mean the service is kaput, which may be too harsh.
      case GET -> Root => Ok(status(conf, genesis, maybeValidatorId).map(_.asJson))
    }
  }
}
