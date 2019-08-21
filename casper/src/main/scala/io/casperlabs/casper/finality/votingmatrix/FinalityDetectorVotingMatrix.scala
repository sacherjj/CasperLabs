package io.casperlabs.casper.finality.votingmatrix

import cats.Monad
import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import cats.mtl.MonadState
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.CommitteeWithConsensusValue
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix._votingMatrixS
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.shared.Log

class FinalityDetectorVotingMatrix[F[_]: Concurrent: Log] private (rFTT: Double)(
    implicit private val matrix: _votingMatrixS[F]
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
                     _      <- updateVoterPerspective[F](dag, blockMetadata, branch)
                     result <- checkForCommittee[F](rFTT)
                     _ <- result match {
                           case Some(newLFB) =>
                             // On new LFB we rebuild VotingMatrix and start the new game.
                             VotingMatrix
                               .create[F](dag, newLFB.consensusValue)
                               .flatMap(_.get.flatMap(matrix.set(_)))
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

object FinalityDetectorVotingMatrix {
  def apply[F[_]](
      implicit detector: FinalityDetectorVotingMatrix[F]
  ): FinalityDetectorVotingMatrix[F] =
    detector

  type _votingMatrixS[F[_]] = MonadState[F, VotingMatrixState] with Semaphore[F]

  private def synchronizedVotingMatrix[F[_]: Monad](
      lock: Semaphore[F],
      state: MonadState[F, VotingMatrixState]
  ): _votingMatrixS[F] =
    new Semaphore[F] with MonadState[F, VotingMatrixState] {

      override def available: F[Long]               = lock.available
      override def count: F[Long]                   = lock.count
      override def acquireN(n: Long): F[Unit]       = lock.acquireN(n)
      override def tryAcquireN(n: Long): F[Boolean] = lock.tryAcquireN(n)
      override def releaseN(n: Long): F[Unit]       = lock.releaseN(n)
      override def withPermit[A](t: F[A]): F[A]     = lock.withPermit(t)

      override val monad: Monad[F]                                            = Monad[F]
      override def get: F[VotingMatrixState]                                  = state.get
      override def set(s: VotingMatrixState): F[Unit]                         = state.set(s)
      override def inspect[A](f: VotingMatrixState => A): F[A]                = state.inspect(f)
      override def modify(f: VotingMatrixState => VotingMatrixState): F[Unit] = state.modify(f)
    }

  /** Constructs an instance of FinalityDetector based on VotingMatrix, starting from `finalizedBlock`.
    *
    * NOTE: Raises an error if rFTT parameters is less than 0 or bigger than 0.5.
    */
  def of[F[_]: Concurrent: Log](
      dag: DagRepresentation[F],
      finalizedBlock: BlockHash,
      rFTT: Double
  ): F[FinalityDetectorVotingMatrix[F]] =
    for {
      _ <- MonadThrowable[F]
            .raiseError(
              new IllegalArgumentException(
                s"Relative FTT has to be bigger than 0 and less than 0.5. Got: $rFTT"
              )
            )
            .whenA(rFTT < 0 || rFTT > 0.5)
      lock                 <- Semaphore[F](1)
      votingMatrix         <- VotingMatrix.create[F](dag, finalizedBlock)
      votingMatrixWithLock = synchronizedVotingMatrix(lock, votingMatrix)
    } yield new FinalityDetectorVotingMatrix[F](rFTT)(Concurrent[F], Log[F], votingMatrixWithLock)
}
