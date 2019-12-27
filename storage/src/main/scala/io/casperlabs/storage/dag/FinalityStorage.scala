package io.casperlabs.storage.dag

import io.casperlabs.storage.block.BlockStorage.BlockHash
import simulacrum.typeclass

@typeclass trait FinalityStorage[F[_]] {
  def markAsFinalized(mainParent: BlockHash, secondary: Set[BlockHash], quorum: BigInt): F[Unit]
  def isFinalized(block: BlockHash): F[Boolean]
}
