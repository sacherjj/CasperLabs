package io.casperlabs.models

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.Block.Role.{BALLOT, BLOCK, Unrecognized}
import io.casperlabs.casper.consensus.{BlockSummary, Bond}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.BlockImplicits._

import scala.util.{Failure, Success, Try}

/** A sum type representing message types that can be exchanged between validators.
  *
  * The intent is to map protobuf messages to internal types as soon as it's possible.
  * This way we can do necessary validation of protobuf fields (like existence of required fields)
  * and represent different messages in type-safe way.
  */
abstract class Message {
  type Id = ByteString
  val messageHash: Id
  val validatorId: ByteString
  val parentBlock: Id
  val justifications: Seq[consensus.Block.Justification]
  val rank: Long
  val validatorMsgSeqNum: Int
  val signature: consensus.Signature

  val parents: Seq[Id]
  val blockSummary: BlockSummary
}

object Message {
  case class Block private (
      messageHash: Message#Id,
      validatorId: ByteString,
      parentBlock: Message#Id,
      justifications: Seq[consensus.Block.Justification],
      rank: Long,
      validatorMsgSeqNum: Int,
      signature: consensus.Signature,
      secondaryParents: Seq[Message#Id],
      weightMap: Map[ByteString, Long],
      blockSummary: BlockSummary
  ) extends Message {
    // For Genesis block we expect it to have no parents.
    // We could either encode it as separate ADT variant or keep the assumptions.
    override val parents: Seq[Id] =
      (parentBlock +: secondaryParents).filterNot(_ == ByteString.EMPTY)
  }

  case class Ballot private (
      messageHash: Message#Id,
      validatorId: ByteString,
      parentBlock: Message#Id,
      justifications: Seq[consensus.Block.Justification],
      rank: Long,
      validatorMsgSeqNum: Int,
      signature: consensus.Signature,
      blockSummary: BlockSummary
  ) extends Message {
    override val parents: Seq[Id] = Seq(parentBlock)
  }

  def fromBlockSummary(b: consensus.BlockSummary): Try[Message] =
    try {
      val messageHash        = b.blockHash
      val header             = b.getHeader
      val parentBlock        = header.parentHashes.headOption.getOrElse(ByteString.EMPTY)
      val creator            = header.validatorPublicKey
      val justifications     = header.justifications
      val rank               = header.rank
      val validatorMsgSeqNum = header.validatorBlockSeqNum
      val messageType        = header.roleType
      val signature          = b.getSignature

      messageType match {
        case BALLOT =>
          Success(
            Ballot(
              messageHash,
              creator,
              parentBlock,
              justifications,
              rank,
              validatorMsgSeqNum,
              signature,
              b
            )
          )
        case BLOCK =>
          val secondaryParents =
            if (header.parentHashes.isEmpty) Seq.empty else header.parentHashes.tail
          val weightMap = header.getState.bonds.map {
            case Bond(validatorPk, stake) => validatorPk -> stake
          }.toMap
          Success(
            Block(
              messageHash,
              creator,
              parentBlock,
              justifications,
              rank,
              validatorMsgSeqNum,
              signature,
              secondaryParents,
              weightMap,
              b
            )
          )
        case Unrecognized(_) =>
          Failure(
            new IllegalArgumentException(
              s"A message ${Base16.encode(messageHash.toByteArray).take(10)} has unrecognized message type."
            )
          )
      }
    } catch {
      case ex: Throwable =>
        val message =
          s"A message ${Base16.encode(b.blockHash.toByteArray).take(10)} could not be parsed to either Block type or a Ballot."
        Failure(new IllegalArgumentException(message, ex))
    }

  def fromBlock(b: consensus.Block): Try[Message] =
    fromBlockSummary(BlockSummary.fromBlock(b))
}
