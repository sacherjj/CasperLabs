package io.casperlabs

import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.BlockMsgWithTransform

package object blockstorage {
  val BlockStorageMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "block-storage")

  val DagStorageMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "dag-storage")

  implicit class RichBlockMsgWithTransform(b: BlockMsgWithTransform) {
    def toBlockSummary: BlockSummary =
      BlockSummary(
        blockHash = b.getBlockMessage.blockHash,
        header = b.getBlockMessage.header,
        signature = b.getBlockMessage.signature
      )
  }
}
