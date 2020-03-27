package io.casperlabs.casper.helper

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.casper.DeployHash
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.EventEmitter
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.shared.Log
import io.casperlabs.storage.dag.FinalityStorage

object NoOpsEventEmitter {
  def create[F[_]: Applicative]: EventEmitter[F] =
    new EventEmitter[F] {
      override def blockAdded(blockHash: BlockHash): F[Unit] = ().pure[F]

      override def newLastFinalizedBlock(
          lfb: BlockHash,
          indirectlyFinalized: Set[BlockHash],
          indirectlyOrphaned: Set[BlockHash]
      ): F[Unit] = ().pure[F]

      override def deployAdded(deploy: Deploy): F[Unit] =
        ().pure[F]
      override def deploysDiscarded(deployHashesWithReasons: Seq[(DeployHash, String)]): F[Unit] =
        ().pure[F]
      override def deploysRequeued(deployHashes: Seq[DeployHash]): F[Unit] =
        ().pure[F]
    }
}
