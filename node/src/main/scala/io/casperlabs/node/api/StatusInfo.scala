package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.node.configuration.Configuration
import org.http4s.HttpRoutes

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

    def peers[F[_]: Sync: NodeDiscovery](conf: Configuration) =
      NodeDiscovery[F].recentlyAlivePeersAscendingDistance.map { nodes =>
        Checked(
          ok = conf.casper.standalone || nodes.nonEmpty,
          message = s"${nodes.length} recently alive peers.".some
        )
      }
  }

  case class CheckList(
      peers: Check.Checked
  ) {
    // I thought about putting everything in a `List[_ <: Check]` and having a custom Json Encoder to
    // show them as an object, or producing a Json object first and parsing the flags, or using Shapeless.
    // This pedestrian version is the most straight forward, but in the future I'd go with Shapeless.
    def ok = peers.ok
  }
  object CheckList {
    def apply[F[_]: Sync: NodeDiscovery](conf: Configuration): F[CheckList] =
      for {
        peers <- Check.peers[F](conf)
        checklist = CheckList(
          peers = peers
        )
      } yield checklist
  }

  def status[F[_]: Sync: NodeDiscovery](conf: Configuration): F[Status] =
    for {
      version   <- Sync[F].delay(VersionInfo.get)
      checklist <- CheckList[F](conf)
    } yield Status(version, checklist.ok, checklist)

  def service[F[_]: Sync: NodeDiscovery](conf: Configuration): HttpRoutes[F] = {
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.circe.CirceEntityEncoder._

    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] {
      // Could return a different HTTP status code, but it really depends on what we want from this.
      // An 50x would mean the service is kaput, which may be too harsh.
      case GET -> Root => Ok(status(conf).map(_.asJson))
    }
  }
}
