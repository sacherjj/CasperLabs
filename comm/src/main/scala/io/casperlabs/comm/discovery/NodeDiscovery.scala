package io.casperlabs.comm.discovery

import io.casperlabs.comm.transport._

import cats.Monad
import cats.data.EitherT
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.{MonadTrans, _}
import io.casperlabs.comm.{CommError, PeerNode}, CommError.CommErr
import io.casperlabs.comm.rp.ProtocolHelper
import io.casperlabs.comm.protocol.routing._

trait NodeDiscovery[F[_]] {
  def discover: F[Unit]
  def peers: F[Seq[PeerNode]]
}

object NodeDiscovery extends NodeDiscoveryInstances {
  def apply[F[_]](implicit L: NodeDiscovery[F]): NodeDiscovery[F] = L

  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](
      implicit C: NodeDiscovery[F]
  ): NodeDiscovery[T[F, ?]] =
    new NodeDiscovery[T[F, ?]] {
      def discover: T[F, Unit]       = C.discover.liftM[T]
      def peers: T[F, Seq[PeerNode]] = C.peers.liftM[T]
    }
}

sealed abstract class NodeDiscoveryInstances {
  implicit def eitherTNodeDiscovery[E, F[_]: Monad: NodeDiscovery[?[_]]]
    : NodeDiscovery[EitherT[F, E, ?]] =
    NodeDiscovery.forTrans[F, EitherT[?[_], E, ?]]
}
