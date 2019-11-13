package io.casperlabs.metrics

import cats._
import cats.data._
import cats.implicits._
import cats.syntax._
import cats.effect.Bracket
import io.casperlabs.catscontrib._
import Catscontrib._

import scala.language.higherKinds

trait Metrics[F[_]] {
  // Counter
  def incrementCounter(name: String, delta: Long = 1)(implicit ev: Metrics.Source): F[Unit]

  // RangeSampler
  def incrementSampler(name: String, delta: Long = 1)(implicit ev: Metrics.Source): F[Unit]
  def sample(name: String)(implicit ev: Metrics.Source): F[Unit]

  // Gauge
  def setGauge(name: String, value: Long)(implicit ev: Metrics.Source): F[Unit]

  def incrementGauge(name: String, delta: Long = 1)(implicit ev: Metrics.Source): F[Unit]

  def decrementGauge(name: String, delta: Long = 1)(implicit ev: Metrics.Source): F[Unit]

  def gauge[A](name: String, delta: Long = 1)(
      block: F[A]
  )(implicit ev: Metrics.Source, B: Bracket[F, Throwable]): F[A] =
    B.guarantee(incrementGauge(name, delta) *> block)(decrementGauge(name, delta))

  def gaugeS[A](name: String, delta: Long = 1)(
      stream: fs2.Stream[F, A]
  )(implicit ev: Metrics.Source): fs2.Stream[F, A] =
    fs2.Stream.bracket(incrementGauge(name, delta))(_ => decrementGauge(name, delta)) >> stream

  // Histogram
  def record(name: String, value: Long, count: Long = 1)(implicit ev: Metrics.Source): F[Unit]

  def timer[A](name: String)(block: F[A])(implicit ev: Metrics.Source): F[A]
  def timerS[A](name: String)(stream: fs2.Stream[F, A])(
      implicit ev: Metrics.Source
  ): fs2.Stream[F, A]
}

object Metrics {
  def apply[F[_]](implicit M: Metrics[F]): Metrics[F] = M

  class MetricsNOP[F[_]: Applicative] extends Metrics[F] {
    def incrementCounter(name: String, delta: Long = 1)(implicit ev: Metrics.Source): F[Unit] =
      ().pure[F]
    def incrementSampler(name: String, delta: Long = 1)(implicit ev: Metrics.Source): F[Unit] =
      ().pure[F]
    def sample(name: String)(implicit ev: Metrics.Source): F[Unit]                      = ().pure[F]
    def setGauge(name: String, value: Long)(implicit ev: Metrics.Source): F[Unit]       = ().pure[F]
    def incrementGauge(name: String, delta: Long)(implicit ev: Metrics.Source): F[Unit] = ().pure[F]
    def decrementGauge(name: String, delta: Long)(implicit ev: Metrics.Source): F[Unit] = ().pure[F]
    def record(name: String, value: Long, count: Long = 1)(implicit ev: Metrics.Source): F[Unit] =
      ().pure[F]
    def timer[A](name: String)(block: F[A])(implicit ev: Metrics.Source): F[A] = block
    def timerS[A](name: String)(stream: fs2.Stream[F, A])(
        implicit ev: Metrics.Source
    ): fs2.Stream[F, A] = stream
  }

  import shapeless.tag.@@
  sealed trait SourceTag
  type Source = String @@ SourceTag
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def Source(name: String): Source = name.asInstanceOf[Source]
  implicit class SourceOps(base: Source) {
    def /(path: String): Source = Source(base, path)
  }
  def Source(prefix: Source, name: String): Source = Source(s"$prefix.$name")
  val BaseSource: Source                           = Source("casperlabs")
}
