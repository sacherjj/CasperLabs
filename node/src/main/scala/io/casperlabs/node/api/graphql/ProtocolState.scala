package io.casperlabs.node.api.graphql

import cats.effect.Fiber

sealed trait ProtocolState extends Product with Serializable
object ProtocolState {
  final case object WaitForInit                             extends ProtocolState
  final case object WaitForStart                            extends ProtocolState
  final case class SendingData[F[_]](fiber: Fiber[F, Unit]) extends ProtocolState
  final case object Closed                                  extends ProtocolState
}
