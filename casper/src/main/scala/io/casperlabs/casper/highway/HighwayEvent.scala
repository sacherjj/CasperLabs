package io.casperlabs.casper.highway

import io.casperlabs.casper.consensus.Era
import io.casperlabs.models.Message

/** Domain events raised during the processing of messages and rounds.
  * Messages in the events will have been created and persisted, but not
  * yet sent to the peers on the network.
  */
sealed trait HighwayEvent

object HighwayEvent {

  /** Created a lambda message; only happens if this validator was leader in the round. */
  case class CreatedLambdaMessage(message: Message) extends HighwayEvent

  /** Created a response to a lambda message received from a leader. */
  case class CreatedLambdaResponse(message: Message.Ballot) extends HighwayEvent

  /** Created an omega message, some time during the round. */
  case class CreatedOmegaMessage(message: Message) extends HighwayEvent

  /** Create a new era based on a previously unused key block. */
  case class CreatedEra(era: Era) extends HighwayEvent
}
