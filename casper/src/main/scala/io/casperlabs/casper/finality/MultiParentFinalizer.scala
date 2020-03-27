package io.casperlabs.casper.finality

import cats.Monad
import cats.implicits._
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.casper.CasperMetricsSource
import io.casperlabs.metrics.implicits._
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
  def onNewMessageAdded(message: Message): F[Seq[FinalizedBlocks]]
}

object MultiParentFinalizer {
  private implicit val metricsSource = CasperMetricsSource / "MultiParentFinalizer"

  class MeteredMultiParentFinalizer[F[_]] private (
      finalizer: MultiParentFinalizer[F],
      metrics: Metrics[F]
  ) extends MultiParentFinalizer[F] {

    override def onNewMessageAdded(message: Message): F[Seq[FinalizedBlocks]] =
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
      indirectlyFinalized: Set[Message],
      indirectlyOrphaned: Set[Message]
  )

  def create[F[_]: Monad: Concurrent: FinalityStorage: Metrics](
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
      override def onNewMessageAdded(message: Message): F[Seq[FinalizedBlocks]] =
        semaphore.withPermit(for {
          previousLFB <- lfbCache.get
          finalizedBlocks <- finalityDetector
                              .onNewMessageAddedToTheBlockDag(dag, message, previousLFB)
                              .timer("onNewMessageAddedToTheBlockDag")
          finalized <- finalizedBlocks.toList.traverse {
                        case CommitteeWithConsensusValue(_, quorum, newLFB) =>
                          for {
                            _ <- lfbCache.set(newLFB)
                            justFinalized <- FinalityDetectorUtil
                                              .finalizedIndirectly[F](
                                                dag,
                                                newLFB,
                                                isHighway
                                              )
                                              .timer("finalizedIndirectly")
                            justOrphaned <- FinalityDetectorUtil
                                             .orphanedIndirectly(
                                               dag,
                                               newLFB,
                                               justFinalized.map(_.messageHash),
                                               isHighway
                                             )
                                             .timer("orphanedIndirectly")
                          } yield FinalizedBlocks(newLFB, quorum, justFinalized, justOrphaned)
                      }
        } yield finalized)
    }
}
