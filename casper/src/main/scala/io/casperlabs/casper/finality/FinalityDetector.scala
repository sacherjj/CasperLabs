package io.casperlabs.casper.finality

import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block
import io.casperlabs.storage.dag.DagRepresentation

trait FinalityDetector[F[_]] {

  /** Signal whether any block is finalized when `block` is added to the DAG. */
  def onNewBlockAddedToTheBlockDag(
      dag: DagRepresentation[F],
      block: Block,
      latestFinalizedBlock: BlockHash
  ): F[Option[CommitteeWithConsensusValue]]
}
