package io.casperlabs.casper.finality

import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation

trait FinalityDetector[F[_]] {

  /** Adds message to the internal finalizer but does not run detection loop.
    */
  def addMessage(
      dag: DagRepresentation[F],
      message: Message,
      latestFinalizedBlock: BlockHash
  ): F[Unit]

  /** Runs finality detection loop using current finalizer state. */
  def checkFinality(dag: DagRepresentation[F]): F[Seq[CommitteeWithConsensusValue]]
}
