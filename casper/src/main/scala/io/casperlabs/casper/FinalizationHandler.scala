package io.casperlabs.casper

import simulacrum.typeclass
@typeclass trait FinalizationHandler[F[_]] {
  def newFinalizedBlock(blockHash: Estimator.BlockHash): F[Unit]
}
