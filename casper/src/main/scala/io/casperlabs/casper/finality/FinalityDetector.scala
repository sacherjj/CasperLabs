package io.casperlabs.casper.finality

import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation

trait FinalityDetector[F[_]] {

  /** Signal whether any block is finalized when `block` is added to the DAG. */
  def onNewMessageAddedToTheBlockDag(
      dag: DagRepresentation[F],
      message: Message,
      latestFinalizedBlock: BlockHash
  ): F[Option[CommitteeWithConsensusValue]]
}
