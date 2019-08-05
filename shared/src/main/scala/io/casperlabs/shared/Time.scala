package io.casperlabs.shared

import cats._
import cats.data._
import io.casperlabs.catscontrib._
import Catscontrib._
import cats.effect.Timer

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, NANOSECONDS}

trait Time[F[_]] {
  def currentMillis: F[Long]
  def nanoTime: F[Long]
  def sleep(duration: FiniteDuration): F[Unit]
}

object Time extends TimeInstances {
  def apply[F[_]](implicit L: Time[F]): Time[F] = L

  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](implicit TM: Time[F]): Time[T[F, ?]] =
    new Time[T[F, ?]] {
      def currentMillis: T[F, Long]                   = TM.currentMillis.liftM[T]
      def nanoTime: T[F, Long]                        = TM.nanoTime.liftM[T]
      def sleep(duration: FiniteDuration): T[F, Unit] = TM.sleep(duration).liftM[T]
    }
}

sealed abstract class TimeInstances {
  implicit def stateTTime[S, F[_]: Monad: Time[?[_]]]: Time[StateT[F, S, ?]] =
    Time.forTrans[F, StateT[?[_], S, ?]]

  implicit def mkTime[F[_]](implicit timer: Timer[F]): Time[F] =
    new Time[F] {
      def currentMillis: F[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: F[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): F[Unit] = timer.sleep(duration)
    }
}
