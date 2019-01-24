package io.casperlabs.casper.util
import io.casperlabs.casper.protocol.BlockMessage

object implicits {
  implicit class RichBlockMessage(b: BlockMessage) {
    def signFunction: (Array[Byte], Array[Byte]) => Array[Byte] =
      SignatureAlgorithms.lookup(b.sigAlgorithm)
  }
}
