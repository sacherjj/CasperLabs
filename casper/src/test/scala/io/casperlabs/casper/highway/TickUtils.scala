package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.Clock
import java.util.Calendar
import java.time.Instant
import scala.concurrent.duration._

trait TickUtils {
  def dateTimestamp(y: Int, m: Int, d: Int): Timestamp = {
    val c = Calendar.getInstance
    c.clear()
    c.set(y, m - 1, d)
    Timestamp(c.getTimeInMillis)
  }

  def days(d: Long)                = d.days
  def hours(h: Long)               = h.hours
  def date(y: Int, m: Int, d: Int) = Instant.ofEpochMilli(dateTimestamp(y, m, d))

  class TestClock[F[_]: Applicative](t: Instant) extends Clock[F] {
    override def realTime(unit: TimeUnit): F[Long] =
      unit.convert(t.toEpochMilli, MILLISECONDS).pure[F]

    override def monotonic(unit: TimeUnit): F[Long] =
      ???
  }
}
