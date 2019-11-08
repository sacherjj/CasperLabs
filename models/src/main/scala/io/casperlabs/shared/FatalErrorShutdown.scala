package io.casperlabs.shared

import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16

object FatalError {
  def selfEquivocationError(blockHash: ByteString): FatalErrorShutdown =
    FatalErrorShutdown(
      s"Node has detected it's own equivocation with block ${Base16.encode(blockHash.toByteArray).take(10)}"
    )
}
