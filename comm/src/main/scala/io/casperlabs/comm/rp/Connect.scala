package io.casperlabs.comm.rp

import cats._
import cats.effect.Sync
import cats.implicits._
import cats.mtl._
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.shared._

import scala.concurrent.duration._

object Connect {

  type Connection            = Node
  type Connections           = List[Connection]
  type ConnectionsCell[F[_]] = Cell[F, Connections]

  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "rp.connect")

  object ConnectionsCell {
    def apply[F[_]](implicit ev: ConnectionsCell[F]): ConnectionsCell[F] = ev
  }

  object Connections {
    def empty: Connections = List.empty[Connection]
    implicit class ConnectionsOps(connections: Connections) {

      def addConn[F[_]: Apply: Log: Metrics](connection: Connection): F[Connections] =
        addConn[F](List(connection))

      def addConn[F[_]: Apply: Log: Metrics](toBeAdded: List[Connection]): F[Connections] = {
        val ids = toBeAdded.map(_.id)
        val newConnections = connections.partition(peer => ids.contains(peer.id)) match {
          case (_, rest) => rest ++ toBeAdded
        }
        val size = newConnections.size.toLong
        Log[F].info(s"Peers: $size.") *>
          Metrics[F].setGauge("peers", size).as(newConnections)
      }

      def removeConn[F[_]: Apply: Log: Metrics](connection: Connection): F[Connections] =
        removeConn[F](List(connection))

      def removeConn[F[_]: Apply: Log: Metrics](toBeRemoved: List[Connection]): F[Connections] = {
        val ids = toBeRemoved.map(_.id)
        val newConnections = connections.partition(peer => ids.contains(peer.id)) match {
          case (_, rest) => rest
        }
        val size = newConnections.size.toLong
        Log[F].info(s"Peers: $size.") *>
          Metrics[F].setGauge("peers", size).as(newConnections)
      }
    }
  }

  type RPConfState[F[_]] = MonadState[F, RPConf]
  type RPConfAsk[F[_]]   = ApplicativeAsk[F, RPConf]

  object RPConfAsk {
    def apply[F[_]](implicit ev: ApplicativeAsk[F, RPConf]): ApplicativeAsk[F, RPConf] = ev
  }

}
