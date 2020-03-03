package io.casperlabs.metrics

import scala.language.higherKinds
import cats.effect.Bracket

trait MetricsBlockSyntax[A, F[_]] {
  def block: F[A]

  def timer(name: String)(implicit M: Metrics[F], ms: Metrics.Source): F[A] =
    M.timer(name)(block)

  def gauge(
      name: String,
      delta: Long = 1
  )(implicit M: Metrics[F], ms: Metrics.Source, B: Bracket[F, Throwable]): F[A] =
    M.gauge(name, delta)(block)

  def timerGauge(
      name: String
  )(implicit M: Metrics[F], ms: Metrics.Source, B: Bracket[F, Throwable]): F[A] =
    M.gauge(s"${name}_ongoing")(M.timer(name)(block))
}

trait MetricsStreamSyntax[A, F[_]] {
  def stream: fs2.Stream[F, A]

  def timer(name: String)(implicit M: Metrics[F], ms: Metrics.Source): fs2.Stream[F, A] =
    M.timerS(name)(stream)

  def gauge(
      name: String,
      delta: Long = 1
  )(implicit M: Metrics[F], ms: Metrics.Source): fs2.Stream[F, A] =
    M.gaugeS(name, delta)(stream)

  def timerGauge(name: String)(implicit M: Metrics[F], ms: Metrics.Source): fs2.Stream[F, A] =
    M.gaugeS(s"${name}_ongoing")(M.timerS(name)(stream))
}

package object implicits {
  implicit final class MetricsBlockSyntaxConversion[A, F[_]](val block: F[A])
      extends MetricsBlockSyntax[A, F]
  implicit final class MetricsStreamSyntaxConversion[A, F[_]](val stream: fs2.Stream[F, A])
      extends MetricsStreamSyntax[A, F]
}
