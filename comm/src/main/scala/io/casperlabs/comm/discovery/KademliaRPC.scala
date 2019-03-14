package io.casperlabs.comm.discovery

import io.casperlabs.comm.{NodeIdentifier, PeerNode}

trait KademliaRPC[F[_]] {
  def ping(node: PeerNode): F[Boolean]
  def lookup(id: NodeIdentifier, peer: PeerNode): F[Seq[PeerNode]]
  def receive(
      pingHandler: PeerNode => F[Unit],
      lookupHandler: (PeerNode, NodeIdentifier) => F[Seq[PeerNode]]
  ): F[Unit]
  def shutdown(): F[Unit]
}

object KademliaRPC {
  def apply[F[_]](implicit P: KademliaRPC[F]): KademliaRPC[F] = P
}
