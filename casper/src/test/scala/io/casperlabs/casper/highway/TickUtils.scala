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

  object TestClock {
    // Strangely if I returned just a `new Clock` it would conflict with the
    // default `implicit def defaultClock` in tests that want to shadow it,
    // but if it's a class then it's happy.
    class Mock[F[_]: Applicative](init: Instant) extends Clock[F] {
      override def realTime(unit: TimeUnit): F[Long] =
        unit.convert(init.toEpochMilli, MILLISECONDS).pure[F]

      override def monotonic(unit: TimeUnit): F[Long] =
        ???
    }

    /** A clock that keeps serving the same time. */
    def frozen[F[_]: Applicative](t: Instant) = new Mock[F](t)
  }

}
