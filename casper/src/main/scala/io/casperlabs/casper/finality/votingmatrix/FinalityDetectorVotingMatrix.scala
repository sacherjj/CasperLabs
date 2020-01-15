package io.casperlabs.casper.finality.votingmatrix

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import cats.mtl.MonadState
import cats.{Applicative, Monad}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.{CommitteeWithConsensusValue, FinalityDetector}
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix._votingMatrixS
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.shared.Log
import io.casperlabs.storage.dag.DagRepresentation

class FinalityDetectorVotingMatrix[F[_]: Concurrent: Log] private (rFTT: Double)(
    implicit private val matrix: _votingMatrixS[F]
) extends FinalityDetector[F] {

  /**
    * Incremental update voting matrix when a new block added to the dag
    * @param dag block dag
    * @param message the new added block
    * @param latestFinalizedBlock latest finalized block
    * @return
    */
  override def onNewMessageAddedToTheBlockDag(
      dag: DagRepresentation[F],
      message: Message,
      latestFinalizedBlock: BlockHash
  ): F[Option[CommitteeWithConsensusValue]] =
    dag.getEquivocators
      .map(_.contains(message.validatorId))
      .ifM(
        none[CommitteeWithConsensusValue].pure[F], {
          matrix
            .withPermit(
              for {
                votedBranch <- ProtoUtil.votedBranch(dag, latestFinalizedBlock, message.messageHash)
                result <- votedBranch match {
                           case Some(branch) =>
                             for {
                               _ <- updateVoterPerspective[F](
                                     dag,
                                     message,
                                     branch
                                   )
                               result <- checkForCommittee[F](dag, rFTT)
                               _ <- result match {
                                     case Some(newLFB) =>
                                       // On new LFB we rebuild VotingMatrix and start the new game.
                                       VotingMatrix
                                         .create[F](dag, newLFB.consensusValue)
                                         .flatMap(_.get.flatMap(matrix.set))
                                     case None =>
                                       Applicative[F].unit
                                   }
                             } yield result

                           // If block doesn't vote on any of main children of latestFinalizedBlock,
                           // then don't update voting matrix
                           case None =>
                             Log[F]
                               .info(
                                 s"The ${PrettyPrinter.buildString(message.messageHash) -> "message"} doesn't vote any main child of ${PrettyPrinter
                                   .buildString(latestFinalizedBlock)                   -> "latestFinalizedBlock"}"
                               )
                               .as(none[CommitteeWithConsensusValue])
                         }
              } yield result
            )
        }
      )
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
    } yield new FinalityDetectorVotingMatrix[F](rFTT) (Concurrent[F], Log[F], votingMatrixWithLock)
}
