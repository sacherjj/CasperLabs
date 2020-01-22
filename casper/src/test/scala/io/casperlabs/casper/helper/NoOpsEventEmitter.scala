package io.casperlabs.casper.helper

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.EventEmitter
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.shared.Log
import io.casperlabs.storage.dag.FinalityStorage

object NoOpsEventEmitter {
  def create[F[_]: Applicative](): EventEmitter[F] =
    new EventEmitter[F] {
      override def blockAdded(block: BlockInfo): F[Unit] = ().pure[F]

      override def newLastFinalizedBlock(
          lfb: BlockHash,
          indirectlyFinalized: Set[BlockHash]
      ): F[Unit] = ().pure[F]
    }
}

object TestEventEmitter {
  // Can't reuse production version because it couples `EventEmitter` with `EventStream`.
  def create[F[_]: Sync: DeployStorage: BlockStorage: FinalityStorage: Log: Metrics]
      : EventEmitter[F] =
    new EventEmitter[F] {
      // Production `EventEmitter` publishes `BlockAdded` event to the observable.
      // We don't need it in tests at the moment.
      override def blockAdded(blockInfo: BlockInfo): F[Unit] = ().pure[F]

      // Mimicks production `EventEmitter` impl.
      // Some tests depend on what is being done there.
      override def newLastFinalizedBlock(
          lfb: BlockHash,
          indirectlyFinalized: Set[BlockHash]
      ): F[Unit] =
        FinalityStorage[F].markAsFinalized(lfb, indirectlyFinalized) >>
          DeployBuffer.removeFinalizedDeploys[F](indirectlyFinalized + lfb)
    }
}
