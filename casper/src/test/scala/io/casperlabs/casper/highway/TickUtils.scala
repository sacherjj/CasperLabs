package io.casperlabs.casper.highway

import java.util.Calendar

trait TickUtils {
  def dateTimestamp(y: Int, m: Int, d: Int): Timestamp = {
    val c = Calendar.getInstance
    c.clear()
    c.set(y, m - 1, d)
    Timestamp(c.getTimeInMillis)
  }

  object MilliTicks {
    def days(d: Long)                = Ticks(d * 24 * 60 * 60 * 1000)
    def date(y: Int, m: Int, d: Int) = Ticks(dateTimestamp(y, m, d))
  }
}
