package io.casperlabs.blockstorage.util

import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.casper.consensus.Block
import io.casperlabs.crypto.codec.Base16

object TopologicalSortUtil {
  type BlockSort = Vector[Vector[BlockHash]]
  def update(sort: BlockSort, offset: Long, block: Block): BlockSort = {
    val hash             = block.blockHash
    val offsetDiff: Long = block.getHeader.rank - offset

    assert(offsetDiff <= Int.MaxValue)
    val number = offsetDiff.toInt

    //block numbers must be sequential, so a new block can only be
    //at a known height or 1 greater than a known height
    if (number > sort.length) {
      throw new java.lang.IllegalArgumentException(
        s"Block ${hex(block.blockHash)} has rank ${number} " +
          s"which is higher then the maximum expected height ${sort.length} at this DAG state. " +
          s"Parents are [${block.getHeader.parentHashes.map(hex).mkString(", ")}]"
      )
    }

    number match {
      //this is a new block height
      case n if n == sort.length => sort :+ Vector(hash)

      //this is another block at a known height
      case n if n < sort.length =>
        val curr = sort(number)
        sort.updated(number, curr :+ hash)
    }
  }

  private def hex(hash: ByteString) = Base16.encode(hash.toByteArray)
}
