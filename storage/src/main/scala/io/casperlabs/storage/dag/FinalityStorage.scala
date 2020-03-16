package io.casperlabs.storage.dag

import cats.Applicative
import cats.implicits._
import io.casperlabs.metrics.Metered
import io.casperlabs.storage.BlockHash
import simulacrum.typeclass

@typeclass trait FinalityStorageReader[F[_]] {
  def getFinalityStatus(block: BlockHash): F[FinalityStorage.FinalityStatus]

  def isFinalized(block: BlockHash)(implicit A: Applicative[F]): F[Boolean] =
    getFinalityStatus(block).map(_.isFinalized)
  def isOrphaned(block: BlockHash)(implicit A: Applicative[F]): F[Boolean] =
    getFinalityStatus(block).map(_.isOrphaned)

  /** Returns last finalized block.
    *
    * A block with the highest rank from the main chain.
    */
  def getLastFinalizedBlock: F[BlockHash]
}

@typeclass trait FinalityStorage[F[_]] extends FinalityStorageReader[F] {
  def markAsFinalized(
      mainParent: BlockHash,
      secondary: Set[BlockHash],
      orphaned: Set[BlockHash]
  ): F[Unit]
}

object FinalityStorage {

  case class FinalityStatus(
      isFinalized: Boolean,
      isOrphaned: Boolean
  ) {
    def isEmpty = !(isFinalized || isOrphaned)
  }

  trait MeteredFinalityStorage[F[_]] extends FinalityStorage[F] with Metered[F] {
    abstract override def markAsFinalized(
        mainParent: BlockHash,
        secondary: Set[BlockHash],
        orphaned: Set[BlockHash]
    ): F[Unit] =
      incAndMeasure("markAsFinalized", super.markAsFinalized(mainParent, secondary, orphaned))

    abstract override def getFinalityStatus(block: BlockHash): F[FinalityStorage.FinalityStatus] =
      incAndMeasure("getFinalityStatus", super.getFinalityStatus(block))

    abstract override def getLastFinalizedBlock: F[BlockHash] =
      incAndMeasure("getLastFinalizedBlock", super.getLastFinalizedBlock)
  }
}
