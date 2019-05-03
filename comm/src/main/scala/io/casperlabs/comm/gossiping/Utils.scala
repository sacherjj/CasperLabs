package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.crypto.codec.Base16

object Utils {
  def hex(summary: BlockSummary): String = Base16.encode(summary.blockHash.toByteArray)

  def hex(bs: ByteString): String = Base16.encode(bs.toByteArray)
}
