package io.casperlabs.comm

import scala.language.higherKinds

import cats.MonadError
import cats.effect.Concurrent
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.casperlabs.catscontrib.ski.kp
import io.casperlabs.shared.Cell
import io.grpc.ManagedChannel
import monix.execution.Cancelable

class CachedConnections[F[_], T](cell: Transport.TransportCell[F])(
    clientChannel: PeerNode => F[ManagedChannel]
)(implicit E: MonadError[F, Throwable]) {

  def connection(peer: PeerNode, enforce: Boolean): F[ManagedChannel] =
    modify { s =>
      if (s.shutdown && !enforce)
        E.raiseError(new RuntimeException("The transport layer has been shut down")).as(s)
      else
        for {
          c <- s.connections.get(peer).fold(clientChannel(peer))(_.pure[F])
        } yield s.copy(connections = s.connections + (peer -> c))
    } >>= kp(cell.read.map(_.connections.apply(peer)))

  def modify(f: TransportState => F[TransportState]): F[Unit] =
    for {
      _ <- cell.flatModify(f)
      s <- read
    } yield ()

  def read: F[TransportState] = cell.read

}

object CachedConnections {
  type ConnectionsCache[F[_], T] = (PeerNode => F[ManagedChannel]) => CachedConnections[F, T]

  def apply[F[_]: Concurrent, T]: F[ConnectionsCache[F, T]] =
    for {
      connections <- Cell.mvarCell[F, TransportState](TransportState.empty)
    } yield new CachedConnections[F, T](connections)(_)
}

object Transport {
  type Connection          = ManagedChannel
  type Connections         = Map[PeerNode, Connection]
  type TransportCell[F[_]] = Cell[F, TransportState]
}

final case class TransportState(
    connections: Transport.Connections = Map.empty,
    server: Option[Cancelable] = None,
    clientQueue: Option[Cancelable] = None,
    shutdown: Boolean = false
)

object TransportState {
  def empty: TransportState = TransportState()
}
