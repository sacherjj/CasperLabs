package io.casperlabs.casper.finality

import cats.Monad
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.FinalityDetector.CommitteeWithConsensusValue
import io.casperlabs.casper.finality.VotingMatrixImpl._votingMatrix
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.shared.Log

class FinalityDetectorVotingMatrix[F[_]: Concurrent: Log](rFTT: Double)(
    implicit matrix: _votingMatrix[F]
) {

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
  ): F[Option[CommitteeWithConsensusValue]] =
    matrix.withPermit(for {
      votedBranch <- ProtoUtil.votedBranch(dag, latestFinalizedBlock, block.blockHash)
      result <- votedBranch match {
                 case Some(branch) =>
                   val blockMetadata = BlockMetadata.fromBlock(block)
                   for {
                     _      <- VotingMatrix.updateVoterPerspective[F](dag, blockMetadata, branch)
                     result <- VotingMatrix.checkForCommittee[F](rFTT)
                     _ <- result match {
                           case Some(newLFB) =>
                             Sync[F].delay(println(s"New LFB: $newLFB")).void
                               VotingMatrixImpl
                                 .create[F](dag, newLFB.consensusValue)
                                 .flatMap(
                                   newVotingMatrix =>
                                     newVotingMatrix.get.flatMap(state => matrix.set(state))
                                 )
                           case None =>
                             ().pure[F]
                         }
                   } yield result

                 // If block doesn't vote on any of main children of latestFinalizedBlock,
                 // then don't update voting matrix
                 case None =>
                   Log[F]
                     .info(
                       s"The block ${PrettyPrinter.buildString(block)} don't vote any main child of latestFinalizedBlock"
                     )
                     .as(none[CommitteeWithConsensusValue])
               }
    } yield result)
}
