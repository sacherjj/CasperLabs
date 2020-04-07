package io.casperlabs.casper.finality

import cats.implicits._
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.CasperMetricsSource
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.metrics.implicits._
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation
import simulacrum.typeclass
import io.casperlabs.storage.dag.FinalityStorage

@typeclass trait MultiParentFinalizer[F[_]] {
  def addMessage(message: Message): F[Unit]

  def checkFinality(): F[Seq[FinalizedBlocks]]
}

object MultiParentFinalizer {
  private implicit val metricsSource = CasperMetricsSource / "MultiParentFinalizer"

  class MeteredMultiParentFinalizer[F[_]] private (
      finalizer: MultiParentFinalizer[F],
      metrics: Metrics[F]
  ) extends MultiParentFinalizer[F] {

    override def addMessage(message: Message): F[Unit] =
      metrics.timer("addMessage")(finalizer.addMessage(message))

    override def checkFinality(): F[Seq[FinalizedBlocks]] =
      metrics.timer("checkFinality")(finalizer.checkFinality())

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

  def create[F[_]: Concurrent: FinalityStorage: Metrics](
      dag: DagRepresentation[F],
      latestLFB: BlockHash, // Last LFB from the main chain
      finalityDetector: FinalityDetector[F]
  ): F[MultiParentFinalizer[F]] =
    for {
      lfbCache  <- Ref[F].of[BlockHash](latestLFB)
      semaphore <- Semaphore[F](1)
    } yield new MultiParentFinalizer[F] {

      override def addMessage(message: Message): F[Unit] =
        semaphore.withPermit(for {
          previousLFB <- lfbCache.get
          _ <- finalityDetector
                .addMessage(dag, message, previousLFB)
        } yield ())

      override def checkFinality(): F[Seq[FinalizedBlocks]] =
        semaphore.withPermit {
          for {
            finalizedBlocks <- finalityDetector.checkFinality(dag)
            finalized <- finalizedBlocks.toList.traverse {
                          case CommitteeWithConsensusValue(_, quorum, newLFB) =>
                            for {
                              _ <- lfbCache.set(newLFB)
                              justFinalized <- FinalityDetectorUtil
                                                .finalizedIndirectly[F](
                                                  dag,
                                                  newLFB
                                                )
                                                .timer("finalizedIndirectly")
                              justOrphaned <- FinalityDetectorUtil
                                               .orphanedIndirectly(
                                                 dag,
                                                 newLFB,
                                                 justFinalized.map(_.messageHash)
                                               )
                                               .timer("orphanedIndirectly")
                              _ <- FinalityStorage[F].markAsFinalized(
                                    newLFB,
                                    justFinalized.map(_.messageHash),
                                    justOrphaned.map(_.messageHash)
                                  )
                            } yield FinalizedBlocks(newLFB, quorum, justFinalized, justOrphaned)
                        }
          } yield finalized
        }
    }
}
