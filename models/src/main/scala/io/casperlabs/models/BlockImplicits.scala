package io.casperlabs.models

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.{GlobalState, Justification}
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.info.BlockInfo.Status.Stats
import io.casperlabs.casper.consensus.state.ProtocolVersion

object BlockImplicits {
  implicit class BlockOps(val block: Block) extends AnyVal {
    def isGenesisLike: Boolean =
      block.getHeader.parentHashes.isEmpty &&
        block.getHeader.validatorPublicKey.isEmpty &&
        block.getSignature.sig.isEmpty

    def parentHashes: Seq[ByteString]        = block.getHeader.parentHashes
    def parents: Seq[ByteString]             = block.getHeader.parentHashes
    def justifications: Seq[Justification]   = block.getHeader.justifications
    def justificationHashes: Seq[ByteString] = block.getHeader.justifications.map(_.latestBlockHash)
    def state: GlobalState                   = block.getHeader.getState
    def bodyHash: ByteString                 = block.getHeader.bodyHash
    def timestamp: Long                      = block.getHeader.timestamp
    def protocolVersion: ProtocolVersion     = block.getHeader.getProtocolVersion
    def deployCount: Int                     = block.getHeader.deployCount
    def chainName: String                    = block.getHeader.chainName
    def validatorBlockSeqNum: Int            = block.getHeader.validatorBlockSeqNum
    def validatorPublicKey: ByteString       = block.getHeader.validatorPublicKey
    def rank: Long                           = block.getHeader.rank
    def weightMap: Map[ByteString, Weight] =
      block.getHeader.getState.bonds
        .map(b => (b.validatorPublicKey, Weight(b.stake)))
        .toMap

    def getSummary: BlockSummary =
      BlockSummary(block.blockHash, block.header, block.signature)
  }

  implicit class BlockSummaryOps(val summary: BlockSummary) extends AnyVal {
    def isGenesisLike: Boolean =
      summary.getHeader.parentHashes.isEmpty &&
        summary.getHeader.validatorPublicKey.isEmpty &&
        summary.getSignature.sig.isEmpty
    def parentHashes: Seq[ByteString]      = summary.getHeader.parentHashes
    def parents: Seq[ByteString]           = summary.getHeader.parentHashes
    def justifications: Seq[Justification] = summary.getHeader.justifications
    def state: GlobalState                 = summary.getHeader.getState
    def bodyHash: ByteString               = summary.getHeader.bodyHash
    def timestamp: Long                    = summary.getHeader.timestamp
    def protocolVersion: ProtocolVersion   = summary.getHeader.getProtocolVersion
    def deployCount: Int                   = summary.getHeader.deployCount
    def chainName: String                  = summary.getHeader.chainName
    def validatorBlockSeqNum: Int          = summary.getHeader.validatorBlockSeqNum
    def validatorPublicKey: ByteString     = summary.getHeader.validatorPublicKey
    def rank: Long                         = summary.getHeader.rank
    def weightMap: Map[ByteString, Weight] =
      summary.getHeader.getState.bonds
        .map(b => (b.validatorPublicKey, Weight(b.stake)))
        .toMap
  }

  implicit class BlockSummaryObjectOps(val blockSummary: BlockSummary.type) extends AnyVal {
    def fromBlock(b: Block): BlockSummary = BlockSummary(b.blockHash, b.header, b.signature)
  }
}
