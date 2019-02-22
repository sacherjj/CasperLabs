package io.casperlabs.comm.transport

import io.casperlabs.comm.protocol.routing.Protocol
import io.casperlabs.comm.CommError

sealed trait CommunicationResponse
final case class HandledWithMessage(pm: Protocol) extends CommunicationResponse
final case object HandledWitoutMessage            extends CommunicationResponse
final case class NotHandled(error: CommError)     extends CommunicationResponse

object CommunicationResponse {
  def handledWithMessage(protocol: Protocol): CommunicationResponse = HandledWithMessage(protocol)
  def handledWithoutMessage: CommunicationResponse                  = HandledWitoutMessage
  def notHandled(error: CommError): CommunicationResponse           = NotHandled(error)
}
