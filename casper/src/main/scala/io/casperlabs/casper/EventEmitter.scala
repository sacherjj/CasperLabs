package io.casperlabs.casper

import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import simulacrum.typeclass

/** Emit events based on the local deploy buffer, which are only
  * observed on the nodes where the deploy was sent.
  */
@typeclass trait DeployEventEmitter[F[_]] {
  def deployAdded(deploy: Deploy): F[Unit]
  def deploysDiscarded(deployHashesWithReasons: Seq[(DeployHash, String)]): F[Unit]
  def deploysRequeued(deployHashes: Seq[DeployHash]): F[Unit]
}

/** Emit events based on whole blocks. Includes raising events for the deploys embedded in the blocks. */
@typeclass trait BlockEventEmitter[F[_]] {
  def blockAdded(blockHash: BlockHash): F[Unit]
  def newLastFinalizedBlock(
      lfb: BlockHash,
      indirectlyFinalized: Set[BlockHash],
      indirectlyOrphaned: Set[BlockHash]
  ): F[Unit]
}

/** Emit events to be observed by the outside world. */
@typeclass trait EventEmitter[F[_]] extends BlockEventEmitter[F] with DeployEventEmitter[F]
