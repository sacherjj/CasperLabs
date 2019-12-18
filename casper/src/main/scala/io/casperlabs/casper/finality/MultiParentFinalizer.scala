package io.casperlabs.casper.finality

import cats.Applicative
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.storage.dag.DagRepresentation
import simulacrum.typeclass

@typeclass trait MultiParentFinalizer[F[_]] {

  /** Returns set of finalized blocks.
    *
    * NOTE: This mimicks [[FinalityDetectorVotingMatrix]] API because we are working with the multi-parent
    * fork choice.
    */
  def onNewBlockAdded(block: Block): F[Option[FinalizedBlocks]]
}

object MultiParentFinalizer {
  final case class FinalizedBlocks(
      mainChain: BlockHash,
      secondaryParents: Set[BlockHash] = Set.empty
  )
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
      override def onNewBlockAdded(block: Block): F[Option[FinalizedBlocks]] =
        semaphore.withPermit(for {
          previousLFB    <- lfbCache.get
          finalizedBlock <- finalityDetector.onNewBlockAddedToTheBlockDag(dag, block, previousLFB)
          finalized <- finalizedBlock.fold(Applicative[F].pure(None: Option[FinalizedBlocks])) {
                        case CommitteeWithConsensusValue(_, _, newLFB) =>
                          for {
                            _              <- lfbCache.set(newLFB)
                            finalizedSoFar <- finalizedBlocksCache.get
                            justFinalized <- FinalityDetectorUtil.finalizedIndirectly[F](
                                              newLFB,
                                              finalizedSoFar,
                                              dag
                                            )
                            _ <- finalizedBlocksCache.update(_ ++ justFinalized + newLFB)
                            // TODO: Persist finalized blocks in the DB.
                          } yield Some(FinalizedBlocks(newLFB, justFinalized))
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
