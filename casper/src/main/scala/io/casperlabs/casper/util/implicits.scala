package io.casperlabs.casper.util
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.crypto.signatures.SignatureAlgorithm

object implicits {
  implicit class RichBlockMessage(b: BlockMessage) {
    def signFunction: (Array[Byte], PrivateKey) => Array[Byte] =
      b.sigAlgorithm match {
        case SignatureAlgorithm(sa) => sa.sign
      }
  }
}
