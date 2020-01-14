package io.casperlabs.casper.finality

import cats.Applicative
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation
import simulacrum.typeclass

@typeclass trait MultiParentFinalizer[F[_]] {

  /** Returns set of finalized blocks.
    *
    * NOTE: This mimicks [[io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix]] API because we are working with the multi-parent
    * fork choice.
    */
  def onNewMessageAdded(message: Message): F[Option[FinalizedBlocks]]
}

object MultiParentFinalizer {
  final case class FinalizedBlocks(
      mainChain: BlockHash,
      quorum: BigInt,
      secondaryParents: Set[BlockHash]
  ) {
    def finalizedBlocks: Set[BlockHash] = secondaryParents + mainChain
  }

  // TODO: Populate `latestLFB` and `finalizedBlocks` from the DB on startup.
  def create[F[_]: Concurrent](
      dag: DagRepresentation[F],
      latestLFB: BlockHash,            // Last LFB from the main chain
      finalizedBlocks: Set[BlockHash], // Set of blocks finalized so far. Will be used as a cache.
      finalityDetector: FinalityDetector[F]
  ): F[MultiParentFinalizer[F]] =
    for {
      finalizedBlocksCache <- Ref[F].of[Set[BlockHash]](finalizedBlocks)
      lfbCache             <- Ref[F].of[BlockHash](latestLFB)
      semaphore            <- Semaphore[F](1)
    } yield new MultiParentFinalizer[F] {

      /** Returns set of finalized blocks */
      override def onNewMessageAdded(message: Message): F[Option[FinalizedBlocks]] =
        semaphore.withPermit(for {
          previousLFB <- lfbCache.get
          finalizedBlock <- finalityDetector
                             .onNewMessageAddedToTheBlockDag(dag, message, previousLFB)
          finalized <- finalizedBlock.fold(Applicative[F].pure(None: Option[FinalizedBlocks])) {
                        case CommitteeWithConsensusValue(_, quorum, newLFB) =>
                          for {
                            _              <- lfbCache.set(newLFB)
                            finalizedSoFar <- finalizedBlocksCache.get
                            justFinalized <- FinalityDetectorUtil.finalizedIndirectly[F](
                                              newLFB,
                                              finalizedSoFar,
                                              dag
                                            )
                            _ <- finalizedBlocksCache.update(_ ++ justFinalized + newLFB)
                          } yield Some(FinalizedBlocks(newLFB, quorum, justFinalized))
                      }
        } yield finalized)
    }

  def empty[F[_]: Concurrent](
      dag: DagRepresentation[F],
      latestLFB: BlockHash,
      finalityDetector: FinalityDetector[F]
  ): F[MultiParentFinalizer[F]] =
    create[F](dag, latestLFB, Set(latestLFB), finalityDetector)
}
