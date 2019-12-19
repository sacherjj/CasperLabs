package io.casperlabs.casper

import shapeless.tag.@@

package highway {
  sealed trait TimestampTag
  sealed trait TicksTag
}

package object highway {

  /** Time since Unix epoch in milliseconds. */
  type Timestamp = Long @@ TimestampTag
  def Timestamp(t: Long) = t.asInstanceOf[Timestamp]

  /** Ticks (a point in time or a duration) in an arbitrary time unit. */
  type Ticks = Long @@ TicksTag
  def Ticks(t: Long) = t.asInstanceOf[Ticks]
  implicit class TicksOps(val a: Ticks) extends AnyVal {
    def plus(b: Ticks)  = Ticks(a + b)
    def minus(b: Ticks) = Ticks(a - b)
  }
}
