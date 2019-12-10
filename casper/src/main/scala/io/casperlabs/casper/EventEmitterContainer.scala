package io.casperlabs.casper

import io.casperlabs.storage.block.BlockStorage.BlockHash
import simulacrum.typeclass

@typeclass trait EventEmitterContainer[F[_]] {
  def blockAdded(blockHash: BlockHash): F[Unit]
}
