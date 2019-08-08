package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.FinalityDetector.Committee
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.shared.Log

class FinalityDetectorVotingMatrix[F[_]: Monad: Log: VotingMatrix] extends FinalityDetector[F] {
  override def normalizedFaultTolerance(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Float] =
    for {
      weights      <- ProtoUtil.mainParentWeightMap(dag, candidateBlockHash)
      committeeOpt <- findCommit(dag, candidateBlockHash, weights)
    } yield committeeOpt
      .map(committee => FinalityDetector.calculateThreshold(committee.quorum, weights.values.sum))
      .getOrElse(0f)

  private def findCommit(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[Option[Committee]] =
    for {
      committeeApproximationOpt <- FinalityDetectorUtil.committeeApproximation(
                                    dag,
                                    candidateBlockHash,
                                    weights
                                  )
      result <- committeeApproximationOpt match {
                 case Some((committeeApproximation, _)) =>
                   VotingMatrix[F].checkForCommittee(
                     candidateBlockHash,
                     committeeApproximation.toSet,
                     (weights.values.sum + 1) / 2,
                     weights
                   )
                 case None =>
                   none[Committee].pure[F]
               }
    } yield result

  override def onNewBlockAddedToTheBlockDag(
      dag: DagRepresentation[F],
      block: Block,
      latestFinalizedBlock: BlockHash
  ): F[Unit] =
    for {
      votedBranch <- ProtoUtil.votedBranch(dag, block.blockHash, latestFinalizedBlock)
      _ <- votedBranch match {
            case Some(branch) =>
              val blockMetadata = BlockMetadata.fromBlock(block)
              VotingMatrix[F].updateVoterPerspective(dag, blockMetadata, branch)
            case None =>
              ().pure[F]
          }
    } yield ()

  // when we finalized a new block, then we need rebuild the whole voting matrix
  override def rebuildFromLatestFinalizedBlock(
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[Unit] =
    VotingMatrix[F].rebuildFromLatestFinalizedBlock(dag, newFinalizedBlock)
}
