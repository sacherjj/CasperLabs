package io.casperlabs.casper.helper

import cats.effect._
import cats.effect.concurrent._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.LastFinalizedBlockHashContainer

object NoOpsLastFinalizedBlockHashContainer {
  def create[F[_]: Concurrent](
      initial: BlockHash
  ): LastFinalizedBlockHashContainer[F] with Object { def set(newLFB: BlockHash): F[Unit] } = {
    val ref = Ref.unsafe[F, BlockHash](initial)
    new LastFinalizedBlockHashContainer[F] {
      override def get: F[BlockHash] =
        ref.get
      def set(newLFB: BlockHash): F[Unit] = ref.set(newLFB)
    }
  }
}
