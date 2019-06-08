package io.casperlabs.casper

import simulacrum.typeclass

@typeclass trait LastFinalizedBlockHashContainer[F[_]] {
  def get: F[Estimator.BlockHash]
  def set(blockHash: Estimator.BlockHash): F[Unit]
}
