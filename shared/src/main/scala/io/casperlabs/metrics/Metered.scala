package io.casperlabs.metrics

import cats.Apply
import cats.syntax.apply._

import io.casperlabs.metrics.implicits._

import scala.language.higherKinds

trait Metered[F[_]] {
  implicit val m: Metrics[F]
  implicit val ms: Metrics.Source
  implicit val a: Apply[F]

  def incAndMeasure[A](name: String, fa: F[A]): F[A] =
    m.incrementCounter(name) *> fa.timer(s"$name-time")
}
