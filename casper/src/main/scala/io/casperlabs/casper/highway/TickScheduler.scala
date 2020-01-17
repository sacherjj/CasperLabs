package io.casperlabs.casper.highway

import cats.implicits._
import cats.effect.{Concurrent, Fiber, Timer}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class TickScheduler[F[_]: Concurrent: Timer](unit: TimeUnit) {
  def scheduleAt[A](ticks: Ticks)(effect: F[A]): F[Fiber[F, A]] =
    for {
      now   <- Timer[F].clock.realTime(unit)
      delay = math.max(ticks - now, 0L)
      fiber <- Concurrent[F].start(delayedEffect(delay, effect))
    } yield fiber

  private def delayedEffect[A](delay: Long, effect: F[A]): F[A] =
    Timer[F].sleep(FiniteDuration(delay, unit)) >> effect
}
