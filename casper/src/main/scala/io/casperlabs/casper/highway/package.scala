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
}
