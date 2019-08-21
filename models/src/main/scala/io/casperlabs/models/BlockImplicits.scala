package io.casperlabs.models

import io.casperlabs.casper.consensus.{Block, BlockSummary}

object BlockImplicits {
  implicit class BlockOps(block: Block) {
    def isGenesisLike: Boolean =
      block.getHeader.parentHashes.isEmpty &&
        block.getHeader.validatorPublicKey.isEmpty &&
        block.getSignature.sig.isEmpty
  }

  implicit class BlockSummaryOps(summary: BlockSummary) {
    def isGenesisLike: Boolean =
      summary.getHeader.parentHashes.isEmpty &&
        summary.getHeader.validatorPublicKey.isEmpty &&
        summary.getSignature.sig.isEmpty
  }
}
