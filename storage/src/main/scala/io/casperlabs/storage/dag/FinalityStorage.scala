package io.casperlabs.storage.dag

import io.casperlabs.storage.block.BlockStorage.BlockHash
import simulacrum.typeclass

@typeclass trait FinalityStorage[F[_]] {
  def markAsFinalized(mainParent: BlockHash, secondary: Set[BlockHash]): F[Unit]
  def isFinalized(block: BlockHash): F[Boolean]

  /** Returns last finalized block and its index in the LFB chain.
    *
    * A block with the highest rank from the main chain.
    */
  def getLastFinalizedBlock: F[(Long, BlockHash)]
}
