package io.casperlabs.casper

import cats.data.WriterT
import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import shapeless.tag.@@

package highway {
  sealed trait TimestampTag
  sealed trait TicksTag
}

package object highway {

  /** Time since Unix epoch in milliseconds. */
  type Timestamp = Long @@ TimestampTag
  def Timestamp(t: Long) = t.asInstanceOf[Timestamp]

  /** Ticks since Unix epoch in the Highway specific time unit. */
  type Ticks = Long @@ TicksTag
  def Ticks(t: Long) = t.asInstanceOf[Ticks]

  implicit class InstantOps(val a: Instant) extends AnyVal {
    def plus(b: FiniteDuration) =
      a.plus(b.length, b.unit.toChronoUnit)

    def minus(b: FiniteDuration) =
      a.minus(b.length, b.unit.toChronoUnit)
  }

  /** Models a state transition of an era, returning the domain events that
    * were raised during the operation. For example a round, or handling the
    * a message may have created a new era, which is now persisted in the
    * database, but scheduling has not been started for it (as we may be in
    * playback mode).
    */
  type HighwayLog[F[_], T] = WriterT[F, Vector[HighwayEvent], T]
}
