package io.casperlabs.shared

import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16

case class SelfEquivocationError(blockHash: ByteString)
    extends Exception(
      s"Node has detected it's own equivocation with block ${Base16.encode(blockHash.toByteArray).take(10)}"
    )
