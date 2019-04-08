package io.casperlabs

import cats.mtl.ApplicativeAsk
import io.casperlabs.comm.discovery.Node
import io.casperlabs.metrics.Metrics

import scala.language.higherKinds

package object comm {
  type NodeAsk[F[_]] = ApplicativeAsk[F, Node]

  object NodeAsk {
    def apply[F[_]](implicit ev: ApplicativeAsk[F, Node]): ApplicativeAsk[F, Node] = ev
  }

  val CommMetricsSource: Metrics.Source = Metrics.Source(Metrics.BaseSource, "comm")
}
