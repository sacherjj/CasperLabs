package io.casperlabs.comm.rp

import cats._
import cats.effect.Sync
import cats.implicits._
import cats.mtl._
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib._
import io.casperlabs.comm.CommError._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.ProtocolHelper._
import io.casperlabs.comm.transport._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._

import scala.concurrent.duration._

object Connect {

  type Connection            = PeerNode
  type Connections           = List[Connection]
  type ConnectionsCell[F[_]] = Cell[F, Connections]

  object ConnectionsCell {
    def apply[F[_]](implicit ev: ConnectionsCell[F]): ConnectionsCell[F] = ev
  }

  object Connections {
    def empty: Connections = List.empty[Connection]
    implicit class ConnectionsOps(connections: Connections) {

      def addConn[F[_]: Monad: Log: Metrics](connection: Connection): F[Connections] =
        addConn[F](List(connection))

      def addConn[F[_]: Monad: Log: Metrics](toBeAdded: List[Connection]): F[Connections] = {
        val ids = toBeAdded.map(_.id)
        val newConnections = connections.partition(peer => ids.contains(peer.id)) match {
          case (_, rest) => rest ++ toBeAdded
        }
        val size = newConnections.size.toLong
        Log[F].info(s"Peers: $size.") *>
          Metrics[F].setGauge("peers", size).as(newConnections)
      }

      def removeConn[F[_]: Monad: Log: Metrics](connection: Connection): F[Connections] =
        removeConn[F](List(connection))

      def removeConn[F[_]: Monad: Log: Metrics](toBeRemoved: List[Connection]): F[Connections] = {
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

  import Connections._

  type RPConfState[F[_]] = MonadState[F, RPConf]
  type RPConfAsk[F[_]]   = ApplicativeAsk[F, RPConf]

  object RPConfAsk {
    def apply[F[_]](implicit ev: ApplicativeAsk[F, RPConf]): ApplicativeAsk[F, RPConf] = ev
  }

  private implicit val logSource: LogSource = LogSource(this.getClass)

  def clearConnections[F[_]: Sync: Time: ConnectionsCell: RPConfAsk: TransportLayer: Log: Metrics]
    : F[Int] = {

    def sendHeartbeat(peer: PeerNode): F[(PeerNode, CommErr[Protocol])] =
      for {
        local   <- RPConfAsk[F].reader(_.local)
        timeout <- RPConfAsk[F].reader(_.defaultTimeout)
        hb      = heartbeat(local)
        res     <- TransportLayer[F].roundTrip(peer, hb, timeout)
      } yield (peer, res)

    def clear(connections: Connections): F[Int] =
      for {
        numOfConnectionsPinged <- RPConfAsk[F].reader(_.clearConnections.numOfConnectionsPinged)
        toPing                 = connections.take(numOfConnectionsPinged)
        results                <- toPing.traverse(sendHeartbeat)
        successfulPeers        = results.collect { case (peer, Right(_)) => peer }
        failedPeers            = results.collect { case (peer, Left(_)) => peer }
        _ <- ConnectionsCell[F].flatModify { connections =>
              connections.removeConn[F](toPing) >>= (_.addConn[F](successfulPeers))
            }
      } yield failedPeers.size

    for {
      connections <- ConnectionsCell[F].read
      max         <- RPConfAsk[F].reader(_.clearConnections.maxNumOfConnections)
      cleared     <- if (connections.size > ((max * 2) / 3)) clear(connections) else 0.pure[F]
    } yield cleared
  }

  def resetConnections[F[_]: Monad: ConnectionsCell: RPConfAsk: TransportLayer: Log: Metrics]
    : F[Unit] =
    ConnectionsCell[F].flatModify { connections =>
      for {
        local  <- RPConfAsk[F].reader(_.local)
        _      <- TransportLayer[F].broadcast(connections, disconnect(local))
        _      <- connections.traverse(TransportLayer[F].disconnect)
        result <- connections.removeConn[F](connections)
      } yield result
    }

  def findAndConnect[F[_]: Sync: Log: Time: Metrics: NodeDiscovery: ErrorHandler: ConnectionsCell: RPConfAsk](
      conn: (PeerNode, FiniteDuration) => F[Unit]
  ): F[List[PeerNode]] =
    for {
      connections      <- ConnectionsCell[F].read
      tout             <- RPConfAsk[F].reader(_.defaultTimeout)
      peers            <- NodeDiscovery[F].peers.map(p => (p.toSet -- connections).toList)
      responses        <- peers.traverse(peer => ErrorHandler[F].attempt(conn(peer, tout)))
      peersAndResonses = peers.zip(responses)
      _ <- peersAndResonses.traverse {
            case (peer, Left(error)) =>
              Log[F].debug(s"Failed to connect to ${peer.toAddress}. Reason: ${error.message}")
            case (peer, Right(_)) =>
              Log[F].info(s"Connected to ${peer.toAddress}.")
          }
    } yield peersAndResonses.filter(_._2.isRight).map(_._1)

  def connect[F[_]: Sync: Log: Time: Metrics: TransportLayer: NodeDiscovery: ErrorHandler: ConnectionsCell: RPConfAsk](
      peer: PeerNode,
      timeout: FiniteDuration
  ): F[Unit] =
    for {
      address  <- Sync[F].delay(peer.toAddress)
      _        <- Log[F].debug(s"Connecting to $address")
      _        <- Metrics[F].incrementCounter("connect")
      _        <- Log[F].debug(s"Initialize protocol handshake to $address")
      local    <- RPConfAsk[F].reader(_.local)
      ph       = protocolHandshake(local)
      response <- TransportLayer[F].roundTrip(peer, ph, timeout * 2) >>= ErrorHandler[F].fromEither
      _ <- Log[F].debug(
            s"Received protocol handshake response from ${ProtocolHelper.sender(response)}."
          )
      _ <- ConnectionsCell[F].flatModify(_.addConn[F](peer))
    } yield ()

}
