package io.casperlabs.casper

import io.casperlabs.casper.consensus.BlockSummary
import simulacrum.typeclass

@typeclass trait EventEmitter[F[_]] {
  def blockAdded(blockSummary: BlockSummary): F[Unit]
}
