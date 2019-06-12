package io.casperlabs.node.api.graphql

import cats.effect.Fiber

sealed trait ProtocolState extends Product with Serializable {
  def name: String
}
object ProtocolState {
  final case object WaitingForInit extends ProtocolState {
    def name: String = "Waiting for init message"
  }
  type Subscriptions[F[_]] = Map[String, Fiber[F, Unit]]
  final case class Active[F[_]](activeSubscriptions: Subscriptions[F]) extends ProtocolState {
    override def name: String = "Active"
  }
  final case object Closed extends ProtocolState {
    def name: String = "Closed"
  }
}
