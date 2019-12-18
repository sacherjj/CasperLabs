package io.casperlabs.casper

import shapeless.tag.@@

package highway {
  sealed trait TimestampTag
  sealed trait TickTag
}

package object highway {

  /** Time since Unix epoch in milliseconds. */
  type Timestamp = Long @@ TimestampTag
  def Timestamp(t: Long) = t.asInstanceOf[Timestamp]

  /** Ticks or rounds in an arbitrary time unit. */
  type Tick = Long @@ TickTag
  def Tick(t: Long) = t.asInstanceOf[Tick]
}
