package io.casperlabs.storage.event

import io.casperlabs.casper.consensus.info.Event

/** Store all the events we return over the gRPC event stream.
  * Give each of them an individual ID so they can be replayed
  * later from the last value a client managed to get earlier,
  * so that they can catch up with anything they missed.
  *
  * IDs are going to be different across nodes.
  */
trait EventStorage[F[_]] {

  /** Store events and assign IDs. */
  def storeEvents(values: Seq[Event.Value]): F[Seq[Event]]

  /** Retrieve events from a given ID onwards to replay them to a client. */
  def getEvents(minId: Long): fs2.Stream[F, Event]
}
