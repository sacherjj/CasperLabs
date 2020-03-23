package io.casperlabs.storage.dag

import cats.Applicative
import cats.implicits._
import io.casperlabs.metrics.Metered
import io.casperlabs.storage.BlockHash
import io.casperlabs.casper.consensus.info.BlockInfo
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
      // The Last Finalized Block, i.e. the block in the main chain that got finalized.
      lfb: BlockHash,
      // Secondary parents that got finalized implicitly by the LFB.
      finalized: Set[BlockHash],
      // Blocks in the j-past cone of the LFB that _didn't_ get finalized.
      orphaned: Set[BlockHash]
  ): F[Unit]
}

object FinalityStorage {

  type FinalityStatus = BlockInfo.Status.Finality
  object FinalityStatus {
    def apply(isFinalized: Boolean, isOrphaned: Boolean): FinalityStatus =
      if (isFinalized) BlockInfo.Status.Finality.FINALIZED
      else if (isOrphaned) BlockInfo.Status.Finality.ORPHANED
      else BlockInfo.Status.Finality.UNDECIDED
  }

  trait MeteredFinalityStorage[F[_]] extends FinalityStorage[F] with Metered[F] {
    abstract override def markAsFinalized(
        mainParent: BlockHash,
        finalized: Set[BlockHash],
        orphaned: Set[BlockHash]
    ): F[Unit] =
      incAndMeasure("markAsFinalized", super.markAsFinalized(mainParent, finalized, orphaned))

    abstract override def getFinalityStatus(block: BlockHash): F[FinalityStorage.FinalityStatus] =
      incAndMeasure("getFinalityStatus", super.getFinalityStatus(block))

    abstract override def getLastFinalizedBlock: F[BlockHash] =
      incAndMeasure("getLastFinalizedBlock", super.getLastFinalizedBlock)
  }

}
