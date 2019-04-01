package io.casperlabs.comm.discovery

import io.casperlabs.comm.{NodeIdentifier, PeerNode}

trait KademliaService[F[_]] {
  def ping(node: PeerNode): F[Boolean]
  def lookup(id: NodeIdentifier, peer: PeerNode): F[Option[Seq[PeerNode]]]
  def receive(
      pingHandler: PeerNode => F[Unit],
      lookupHandler: (PeerNode, NodeIdentifier) => F[Seq[PeerNode]]
  ): F[Unit]
  def shutdown(): F[Unit]
}

object KademliaService {
  def apply[F[_]](implicit P: KademliaService[F]): KademliaService[F] = P
}
