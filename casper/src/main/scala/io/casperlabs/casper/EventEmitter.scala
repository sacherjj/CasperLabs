package io.casperlabs.casper

import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.info.BlockInfo
import simulacrum.typeclass

@typeclass trait EventEmitter[F[_]] {
  def blockAdded(blockInfo: BlockInfo): F[Unit]
  def newLFB(lfb: BlockHash, indirectlyFinalized: Set[BlockHash] = Set.empty): F[Unit]
}
