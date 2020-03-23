package io.casperlabs.casper.finality

import cats.Applicative
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.casper.CasperMetricsSource
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation
import simulacrum.typeclass
import io.casperlabs.storage.dag.FinalityStorage

@typeclass trait MultiParentFinalizer[F[_]] {

  /** Returns set of finalized blocks.
    *
    * NOTE: This mimicks [[io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix]] API because we are working with the multi-parent
    * fork choice.
    */
  def onNewMessageAdded(message: Message): F[Option[FinalizedBlocks]]
}

object MultiParentFinalizer {
  class MeteredMultiParentFinalizer[F[_]] private (
      finalizer: MultiParentFinalizer[F],
      metrics: Metrics[F]
  ) extends MultiParentFinalizer[F] {

    private implicit val metricsSource = CasperMetricsSource / "MultiParentFinalizer"

    override def onNewMessageAdded(message: Message): F[Option[FinalizedBlocks]] =
      metrics.timer("onNewMessageAdded")(finalizer.onNewMessageAdded(message))
  }

  object MeteredMultiParentFinalizer {
    def of[F[_]](
        f: MultiParentFinalizer[F]
    )(implicit M: Metrics[F]): MeteredMultiParentFinalizer[F] =
      new MeteredMultiParentFinalizer[F](f, M)
  }

  final case class FinalizedBlocks(
      // New finalized block in the main chain.
      newLFB: BlockHash,
      quorum: BigInt,
      indirectlyFinalized: Set[BlockHash],
      indirectlyOrphaned: Set[BlockHash]
  ) {
    def finalizedBlocks: Set[BlockHash] = indirectlyFinalized + newLFB
  }

  def create[F[_]: Concurrent: FinalityStorage](
      dag: DagRepresentation[F],
      latestLFB: BlockHash, // Last LFB from the main chain
      finalityDetector: FinalityDetector[F],
      isHighway: Boolean
  ): F[MultiParentFinalizer[F]] =
    for {
      lfbCache  <- Ref[F].of[BlockHash](latestLFB)
      semaphore <- Semaphore[F](1)
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
                            _ <- lfbCache.set(newLFB)
                            justFinalized <- FinalityDetectorUtil.finalizedIndirectly[F](
                                              dag,
                                              newLFB,
                                              isHighway
                                            )
                            justOrphaned <- FinalityDetectorUtil.orphanedIndirectly(
                                             dag,
                                             newLFB,
                                             justFinalized,
                                             isHighway
                                           )
                          } yield Some(
                            FinalizedBlocks(newLFB, quorum, justFinalized, justOrphaned)
                          )
                      }
        } yield finalized)
    }
}
