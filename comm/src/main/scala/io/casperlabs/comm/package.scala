package io.casperlabs

import cats.mtl.ApplicativeAsk
import io.casperlabs.comm.discovery.Node
import io.casperlabs.metrics.Metrics

import scala.language.higherKinds

package object comm {
  type NodeAsk[F[_]]       = ApplicativeAsk[F, Node]
  type BootstrapsAsk[F[_]] = ApplicativeAsk[F, List[Node]]

  object NodeAsk {
    def apply[F[_]](implicit ev: ApplicativeAsk[F, Node]): ApplicativeAsk[F, Node] = ev
  }

  object BootstrapsAsk {
    def apply[F[_]](implicit ev: ApplicativeAsk[F, List[Node]]): ApplicativeAsk[F, List[Node]] = ev
  }

  val CommMetricsSource: Metrics.Source = Metrics.Source(Metrics.BaseSource, "comm")
}
