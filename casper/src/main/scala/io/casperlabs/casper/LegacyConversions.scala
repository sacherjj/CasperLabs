package io.casperlabs.casper

import io.casperlabs.casper.consensus
import io.casperlabs.casper.protocol

object LegacyConversions {
  def toBlockSummary(block: protocol.BlockMessage): consensus.BlockSummary = ???
  def toBlock(block: protocol.BlockMessage): consensus.Block               = ???
  def fromBlock(block: consensus.Block): protocol.BlockMessage             = ???
}
