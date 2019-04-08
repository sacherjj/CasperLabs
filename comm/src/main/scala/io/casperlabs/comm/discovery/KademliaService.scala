package io.casperlabs.comm.discovery

trait KademliaService[F[_]] {
  def ping(node: Node): F[Boolean]
  def lookup(id: NodeIdentifier, peer: Node): F[Option[Seq[Node]]]
  def receive(
      pingHandler: Node => F[Unit],
      lookupHandler: (Node, NodeIdentifier) => F[Seq[Node]]
  ): F[Unit]
  def shutdown(): F[Unit]
}

object KademliaService {
  def apply[F[_]](implicit P: KademliaService[F]): KademliaService[F] = P
}
