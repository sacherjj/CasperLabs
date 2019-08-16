package io.casperlabs.blockstorage

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, Bond}, Block.Justification
import io.casperlabs.storage.BlockMetadataInternal
import scalapb.TypeMapper

final case class BlockMetadata(
    blockHash: ByteString,
    parents: List[ByteString],
    validatorPublicKey: ByteString,
    justifications: List[Justification],
    weightMap: Map[ByteString, Long],
    rank: Long,
    validatorBlockSeqNum: Int
) {
  def toByteString = BlockMetadata.typeMapper.toBase(this).toByteString
}

object BlockMetadata {
  implicit val typeMapper = TypeMapper[BlockMetadataInternal, BlockMetadata] { internal =>
    BlockMetadata(
      internal.blockHash,
      internal.parents,
      internal.validatorPublicKey,
      internal.justifications,
      internal.bonds.map(b => b.validatorPublicKey -> b.stake).toMap,
      internal.rank,
      internal.validatorBlockSeqNum
    )
  } { metadata =>
    BlockMetadataInternal(
      metadata.blockHash,
      metadata.parents,
      metadata.validatorPublicKey,
      metadata.justifications,
      metadata.weightMap.map { case (validator, stake) => Bond(validator, stake) }.toList,
      metadata.rank,
      metadata.validatorBlockSeqNum
    )
  }

  def fromBytes(bytes: Array[Byte]): BlockMetadata =
    typeMapper.toCustom(BlockMetadataInternal.parseFrom(bytes))

  def fromBlock(b: Block): BlockMetadata =
    BlockMetadata(
      b.blockHash,
      b.getHeader.parentHashes.toList,
      b.getHeader.validatorPublicKey,
      b.getHeader.justifications.toList,
      b.getHeader.getState.bonds.map { bond =>
        bond.validatorPublicKey -> bond.stake
      }.toMap,
      b.getHeader.rank,
      b.getHeader.validatorBlockSeqNum
    )
}
