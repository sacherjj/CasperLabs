package io.casperlabs.casper.util
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.models.MessageSummary

object implicits {
  implicit class RichBlockMessage(b: BlockMessage) {
    def signFunction: (Array[Byte], PrivateKey) => Array[Byte] =
      b.sigAlgorithm match {
        case SignatureAlgorithm(sa) => sa.sign
      }
  }

  implicit val showBlockHash: cats.Show[BlockHash] = cats.Show.show(PrettyPrinter.buildString)
  implicit val showBlockSummary: cats.Show[BlockSummary] =
    cats.Show.show(m => PrettyPrinter.buildString(m.blockHash))
  implicit val eqBlockHash: cats.Eq[BlockHash] = (x: BlockHash, y: BlockHash) => x == y
  implicit val eqBlockSummary: cats.Eq[BlockSummary] = (x: BlockSummary, y: BlockSummary) =>
    x.blockHash == y.blockHash
  implicit val eqMessageSummary: cats.Eq[MessageSummary] = (x: MessageSummary, y: MessageSummary) =>
    x.messageHash == y.messageHash
}
