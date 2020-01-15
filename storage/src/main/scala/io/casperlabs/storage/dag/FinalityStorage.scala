package io.casperlabs.storage.dag

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
