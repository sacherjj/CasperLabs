package io.casperlabs.storage.dag

import io.casperlabs.metrics.Metered
import io.casperlabs.storage.BlockHash
import simulacrum.typeclass

@typeclass trait FinalityStorageReader[F[_]] {
  def isFinalized(block: BlockHash): F[Boolean]

  /** Returns last finalized block.
    *
    * A block with the highest rank from the main chain.
    */
  def getLastFinalizedBlock: F[BlockHash]
}

@typeclass trait FinalityStorage[F[_]] extends FinalityStorageReader[F] {
  def markAsFinalized(mainParent: BlockHash, secondary: Set[BlockHash]): F[Unit]
}

object FinalityStorage {

  trait MeteredFinalityStorage[F[_]] extends FinalityStorage[F] with Metered[F] {
    abstract override def markAsFinalized(
        mainParent: BlockHash,
        secondary: Set[BlockHash]
    ): F[Unit] =
      incAndMeasure("markAsFinalized", super.markAsFinalized(mainParent, secondary))

    abstract override def isFinalized(block: BlockHash): F[Boolean] =
      incAndMeasure("isFinalized", super.isFinalized(block))

    abstract override def getLastFinalizedBlock: F[BlockHash] =
      incAndMeasure("getLastFinalizedBlock", super.getLastFinalizedBlock)
  }
}
