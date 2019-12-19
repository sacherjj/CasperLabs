package io.casperlabs.casper

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
    def plus(b: FiniteDuration)  = a.plusNanos(b.toNanos)
    def minus(b: FiniteDuration) = a.minusNanos(b.toNanos)
  }

}
