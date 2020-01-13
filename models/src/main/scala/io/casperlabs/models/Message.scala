package io.casperlabs.models

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.Block.MessageType.{BALLOT, BLOCK, Unrecognized}
import io.casperlabs.casper.consensus.{BlockSummary, Bond}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.catscontrib.MonadThrowable
import cats.implicits._

import scala.util.{Failure, Success, Try}

/** A sum type representing message types that can be exchanged between validators.
  *
  * The intent is to map protobuf messages to internal types as soon as it's possible.
  * This way we can do necessary validation of protobuf fields (like existence of required fields)
  * and represent different messages in type-safe way.
  */
sealed trait Message {
  type Id = ByteString
  val messageHash: Id
  val validatorId: ByteString
  val timestamp: Long
  val parentBlock: Id
  val justifications: Seq[consensus.Block.Justification]
  val rank: Long
  val validatorMsgSeqNum: Int
  val signature: consensus.Signature

  val parents: Seq[Id]
  val blockSummary: BlockSummary

  val validatorPrevMessageHash: Id

  def isGenesisLike: Boolean =
    this.parents.isEmpty &&
      this.validatorId.isEmpty &&
      this.signature.sig.isEmpty
}

object Message {
  case class Block private (
      messageHash: Message#Id,
      validatorId: ByteString,
      timestamp: Long,
      parentBlock: Message#Id,
      justifications: Seq[consensus.Block.Justification],
      rank: Long,
      validatorMsgSeqNum: Int,
      signature: consensus.Signature,
      blockSummary: BlockSummary,
      validatorPrevMessageHash: Message#Id
  ) extends Message {
    // For Genesis block we expect it to have no parents.
    // We could either encode it as separate ADT variant or keep the assumptions.
    override val parents: Seq[Id] =
      (parentBlock +: secondaryParents).filterNot(_ == ByteString.EMPTY)

    lazy val secondaryParents =
      if (blockSummary.getHeader.parentHashes.isEmpty) Seq.empty
      else blockSummary.getHeader.parentHashes.tail

    lazy val weightMap: Map[ByteString, Weight] = blockSummary.getHeader.getState.bonds.map {
      case Bond(validatorPk, stake) => validatorPk -> Weight(stake)
    }.toMap
  }

  case class Ballot private (
      messageHash: Message#Id,
      validatorId: ByteString,
      timestamp: Long,
      parentBlock: Message#Id,
      justifications: Seq[consensus.Block.Justification],
      rank: Long,
      validatorMsgSeqNum: Int,
      signature: consensus.Signature,
      blockSummary: BlockSummary,
      validatorPrevMessageHash: Message#Id
  ) extends Message {
    override val parents: Seq[Id] = Seq(parentBlock)
  }

  def fromBlockSummary(b: consensus.BlockSummary): Try[Message] =
    try {
      val messageHash        = b.blockHash
      val header             = b.getHeader
      val timestamp          = header.timestamp
      val parentBlock        = header.parentHashes.headOption.getOrElse(ByteString.EMPTY)
      val validatorId        = header.validatorPublicKey
      val justifications     = header.justifications
      val rank               = header.rank
      val validatorMsgSeqNum = header.validatorBlockSeqNum
      val role               = header.messageType
      val signature          = b.getSignature
      val prevMsgHash        = header.validatorPrevBlockHash

      role match {
        case BALLOT =>
          Success(
            Ballot(
              messageHash,
              validatorId,
              timestamp,
              parentBlock,
              justifications,
              rank,
              validatorMsgSeqNum,
              signature,
              b,
              prevMsgHash
            )
          )
        case BLOCK =>
          Success(
            Block(
              messageHash,
              validatorId,
              timestamp,
              parentBlock,
              justifications,
              rank,
              validatorMsgSeqNum,
              signature,
              b,
              prevMsgHash
            )
          )
        case Unrecognized(_) =>
          Failure(
            new IllegalArgumentException(
              s"The message ${Base16.encode(messageHash.toByteArray).take(10)} has unrecognized message type."
            )
          )
      }
    } catch {
      case ex: Throwable =>
        val message =
          s"The message ${Base16.encode(b.blockHash.toByteArray).take(10)} could not be parsed to either Block type or a Ballot."
        Failure(new IllegalArgumentException(message, ex))
    }

  def fromBlock(b: consensus.Block): Try[Message] =
    fromBlockSummary(BlockSummary.fromBlock(b))

  def fromOptionalSummary[F[_]: MonadThrowable](
      b: Option[consensus.BlockSummary]
  ): F[Option[Message]] =
    b.fold(none[Message].pure[F])(
      bs => MonadThrowable[F].fromTry(fromBlockSummary(bs)).map(Some(_))
    )
}
