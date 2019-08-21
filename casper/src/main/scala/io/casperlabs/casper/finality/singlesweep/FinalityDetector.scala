package io.casperlabs.casper.finality.singlesweep

import io.casperlabs.blockstorage.DagRepresentation
import io.casperlabs.casper.Estimator.{BlockHash, Validator}

trait FinalityDetector[F[_]] {

  /**
    * The normalizedFaultTolerance must be greater than
    * the fault tolerance threshold t in order for a candidate to be safe.
    * The range of t is [-1,1], and a positive t means the fraction of validators would have
    * to equivocate to revert the decision on the block, a negative t means unless that fraction
    * equivocates, the block can't get finalized. (I.e. it's orphaned.)
    *
    * @param candidateBlockHash Block hash of candidate block to detect safety on
    * @return normalizedFaultTolerance float between -1 and 1
    */
  def normalizedFaultTolerance(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Float]
}

object FinalityDetector {
  def apply[F[_]](implicit ev: FinalityDetector[F]): FinalityDetector[F] = ev

  // Calculate threshold value as described in the specification.
  // Note that validator weights (`q` and `n`) are normalized to 1.
  private[casper] def calculateThreshold(q: Long, n: Long): Float = (2.0f * q - n) / (2 * n)
}
