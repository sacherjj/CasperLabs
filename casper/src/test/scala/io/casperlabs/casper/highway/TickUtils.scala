package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.{Clock, Concurrent, Sync, Timer}
import cats.effect.concurrent.Ref
import monix.execution.ExecutionModel
import monix.execution.schedulers.TestScheduler
import java.util.Calendar
import java.time.Instant
import java.util.concurrent.TimeUnit
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
    class Mock[F[_]: Applicative](protected val now: Ref[F, Instant]) extends Clock[F] {
      override def realTime(unit: TimeUnit): F[Long] =
        now.get.map(t => unit.convert(t.toEpochMilli, MILLISECONDS))

      override def monotonic(unit: TimeUnit): F[Long] =
        ???
    }

    class AdjustableMock[F[_]: Sync](now: Ref[F, Instant]) extends Mock[F](now) {
      def set(t: Instant): F[Unit] = now.set(t)
    }

    /** A clock that keeps serving the same time. */
    def frozen[F[_]: Sync](t: Instant) = new Mock[F](Ref.unsafe(t))

    /** A clock we can move. */
    def adjustable[F[_]: Sync](t: Instant) = new AdjustableMock[F](Ref.unsafe(t))
  }

  /** Create a TestScheduler adjusted to a given tick in time that we can use with
    * wall clock time based eras. Adjustments can be made with `ctx.setInstant` or `ctx.tick`.
    */
  implicit class TestSchedulerCompanionOps(typ: TestScheduler.type) {
    def fromInstant(t: Instant): TestScheduler = {
      val ctx = TestScheduler()
      ctx.tick(t.toEpochMilli.millis)
      ctx
    }
  }

  implicit class TestSchedulerOps(ctx: TestScheduler) {

    /** Set the clock forward and execute all queued tasks. */
    def forwardTo(t: Instant): Unit = {
      val currentMillis = ctx.clockRealTime(TimeUnit.MILLISECONDS)
      val delay         = t.toEpochMilli - currentMillis
      assert(delay >= 0, "Cannot wind the clock backwards!")
      ctx.tick(delay.millis)
    }
  }

}
