package io.casperlabs.casper

import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import simulacrum.typeclass

@typeclass trait EventEmitter[F[_]] {
  def blockAdded(blockInfo: BlockInfo): F[Unit]
  def newLastFinalizedBlock(
      lfb: BlockHash,
      indirectlyFinalized: Set[BlockHash]
  ): F[Unit]

  // The following `deploy*` methods are based on the local deploy buffer,
  // they are not observed on every node (unless there's deploy gossiping).
  // The missing ones (deployProcessed, deployFinalized) can be handled
  // internally by the `blockAdded` and `newLastFinalizedBlock` methods.

  def deployAdded(deploy: Deploy): F[Unit]
  def deployDiscarded(deployHash: DeployHash, message: String): F[Unit]
  def deployRequeued(deployHash: DeployHash): F[Unit]
}
