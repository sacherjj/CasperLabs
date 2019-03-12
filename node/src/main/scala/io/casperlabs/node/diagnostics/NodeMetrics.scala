package io.casperlabs.node.diagnostics

import cats.Monad
import cats.data.EitherT

import io.casperlabs.catscontrib.MonadTrans
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.node.api.diagnostics.NodeCoreMetrics

trait NodeMetrics[F[_]] {
  def metrics: F[NodeCoreMetrics]
}

object NodeMetrics extends NodeMetricsInstances {
  def apply[F[_]](implicit M: NodeMetrics[F]): NodeMetrics[F] = M

  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](
      implicit C: NodeMetrics[F]
  ): NodeMetrics[T[F, ?]] =
    new NodeMetrics[T[F, ?]] {
      def metrics: T[F, NodeCoreMetrics] = C.metrics.liftM[T]
    }
}

sealed abstract class NodeMetricsInstances {
  implicit def eitherTNodeMetrics[E, F[_]: Monad: NodeMetrics[?[_]]]
    : NodeMetrics[EitherT[F, E, ?]] =
    NodeMetrics.forTrans[F, EitherT[?[_], E, ?]]
}
