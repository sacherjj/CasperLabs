package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.FinalityDetector.{Committee, CommitteeWithConsensusValue}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.shared.Log

class FinalityDetectorVotingMatrix[F[_]: Monad: Log: VotingMatrix] {

  /**
    * Find the next to be finalized block from main children of latestFinalizedBlock
    * @param dag block dag
    * @param rFTT relative fault tolerance threshold
    * @return
    */
  def findCommittee(
      dag: DagRepresentation[F],
      rFTT: Double
  ): F[Option[CommitteeWithConsensusValue]] =
    VotingMatrix[F].checkForCommittee(dag, rFTT)

  /**
    * Incremental update voting matrix when a new block added to the dag
    * @param dag block dag
    * @param block the new added block
    * @param latestFinalizedBlock latest finalized block
    * @return
    */
  def onNewBlockAddedToTheBlockDag(
      dag: DagRepresentation[F],
      block: Block,
      latestFinalizedBlock: BlockHash
  ): F[Unit] =
    for {
      votedBranch <- ProtoUtil.votedBranch(dag, latestFinalizedBlock, block.blockHash)
      _ <- votedBranch match {
            case Some(branch) =>
              val blockMetadata = BlockMetadata.fromBlock(block)
              VotingMatrix[F].updateVoterPerspective(dag, blockMetadata, branch)
            // If block don't vote any main child of latestFinalizedBlock,
            // then don't update voting matrix
            case None =>
              Log[F].info(
                s"The block ${PrettyPrinter.buildString(block)} don't vote any main child of latestFinalizedBlock"
              )
          }
    } yield ()

  /**
    * When a new block get finalized, rebuild the whole voting
    * matrix basing the new finalized block
    *
    * @param dag
    * @param newFinalizedBlock
    * @return
    */
  def rebuildFromLatestFinalizedBlock(
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[Unit] =
    VotingMatrix[F].rebuildFromLatestFinalizedBlock(dag, newFinalizedBlock)
}
