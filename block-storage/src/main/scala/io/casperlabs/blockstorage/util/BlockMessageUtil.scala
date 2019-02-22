package io.casperlabs.blockstorage.util

import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.{BlockMessage, Bond, RChainState}

object BlockMessageUtil {
  // TODO: Remove once optional fields are removed
  def blockNumber(b: BlockMessage): Long =
    (for {
      bd <- b.body
      ps <- bd.state
    } yield ps.blockNumber).getOrElse(0L)

  def bonds(b: BlockMessage): Seq[Bond] =
    (for {
      bd <- b.body
      ps <- bd.state
    } yield ps.bonds).getOrElse(List.empty[Bond])

  def parentHashes(b: BlockMessage): Seq[ByteString] =
    b.header.fold(Seq.empty[ByteString])(_.parentsHashList)
}
