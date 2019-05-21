package io.casperlabs.blockstorage.util

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, Bond}

object BlockMessageUtil {
  // TODO: Remove once optional fields are removed
  def blockNumber(b: Block): Long =
    b.getHeader.rank

  def bonds(b: Block): Seq[Bond] =
    b.getHeader.getState.bonds

  def parentHashes(b: Block): Seq[ByteString] =
    b.getHeader.parentHashes
}
