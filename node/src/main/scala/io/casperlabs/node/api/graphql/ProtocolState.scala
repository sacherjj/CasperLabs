package io.casperlabs.node.api.graphql

import cats.effect.Fiber

sealed trait ProtocolState extends Product with Serializable
object ProtocolState {
  final case object WaitForInit extends ProtocolState
  type Subscriptions[F[_]] = Map[String, Fiber[F, Unit]]
  final case class Active[F[_]](activeSubscriptions: Subscriptions[F]) extends ProtocolState
  final case object Closed                                             extends ProtocolState
}
