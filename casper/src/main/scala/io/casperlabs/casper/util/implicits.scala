package io.casperlabs.casper.util

import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.models.Message

object implicits {
  implicit val showBlockHash: cats.Show[BlockHash] = cats.Show.show(PrettyPrinter.buildString)
  implicit val showBlockSummary: cats.Show[BlockSummary] =
    cats.Show.show(m => PrettyPrinter.buildString(m.blockHash))
  implicit val eqBlockHash: cats.Eq[BlockHash] = (x: BlockHash, y: BlockHash) => x == y
  implicit val eqBlockSummary: cats.Eq[BlockSummary] = (x: BlockSummary, y: BlockSummary) =>
    x.blockHash == y.blockHash
  implicit val eqMessageSummary: cats.Eq[Message] = (x: Message, y: Message) =>
    x.messageHash == y.messageHash
}
