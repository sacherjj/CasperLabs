package io.casperlabs.casper.util

import io.casperlabs.blockstorage.BlockMetadata
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter

object implicits {
  implicit val showBlockHash: cats.Show[BlockHash] = cats.Show.show(PrettyPrinter.buildString)
  implicit val showBlockMetadata: cats.Show[BlockMetadata] =
    cats.Show.show(m => PrettyPrinter.buildString(m.blockHash))
  implicit val eqBlockHash: cats.Eq[BlockHash] = (x: BlockHash, y: BlockHash) => x == y
  implicit val eqBlockMetadata: cats.Eq[BlockMetadata] = (x: BlockMetadata, y: BlockMetadata) =>
    x.blockHash == y.blockHash
}
