package io.casperlabs.metrics

import scala.language.higherKinds
import cats.effect.Bracket

trait MetricsSyntax[A, F[_]] {
  def block: F[A]

  def timer(name: String)(implicit M: Metrics[F], ms: Metrics.Source): F[A] =
    M.timer(name)(block)

  def gauge(
      name: String,
      delta: Long = 1
  )(implicit M: Metrics[F], ms: Metrics.Source, B: Bracket[F, Throwable]): F[A] =
    M.gauge(name, delta)(block)
}

package object implicits {
  implicit final class MetricsSyntaxConversion[A, F[_]](val block: F[A]) extends MetricsSyntax[A, F]
}
