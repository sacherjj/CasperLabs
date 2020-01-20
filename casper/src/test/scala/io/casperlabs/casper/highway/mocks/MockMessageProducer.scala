package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.effect.Sync
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond}
import io.casperlabs.casper.highway.{MessageProducer, Ticks}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorageWriter
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.models.Message

class MockMessageProducer[F[_]: Sync: BlockStorageWriter: DagStorage](
    val validatorId: PublicKeyBS
) extends MessageProducer[F] {

  private def insert(message: Message): F[Unit] = {
    val summary = message.blockSummary
    BlockStorageWriter[F]
      .put(
        BlockMsgWithTransform().withBlockMessage(
          Block(
            blockHash = summary.blockHash,
            header = summary.header,
            signature = summary.signature
          )
        )
      )
      .void
  }

  override def ballot(
      eraId: BlockHash,
      roundId: Ticks,
      target: ByteString,
      justifications: Map[PublicKeyBS, Set[BlockHash]]
  ): F[Message.Ballot] =
    for {
      dag    <- DagStorage[F].getRepresentation
      parent <- dag.lookupUnsafe(target)
      unsigned = BlockSummary()
        .withHeader(
          Block
            .Header()
            .withMessageType(Block.MessageType.BALLOT)
            .withValidatorPublicKey(validatorId)
            .withParentHashes(List(target))
            .withJustifications(
              for {
                kv <- justifications.toList
                h  <- kv._2.toList
              } yield Block.Justification(kv._1, h)
            )
            .withRoundId(roundId)
            .withKeyBlockHash(eraId)
            .withState(
              Block.GlobalState().withBonds(parent.blockSummary.getHeader.getState.bonds)
            )
        )
      hash   = ProtoUtil.protoHash(unsigned)
      signed = unsigned.withBlockHash(hash)

      msg <- Sync[F].fromTry(Message.fromBlockSummary(signed)).map(_.asInstanceOf[Message.Ballot])
      _   <- insert(msg)
    } yield msg

  override def block(
      eraId: ByteString,
      roundId: Ticks,
      mainParent: ByteString,
      justifications: Map[PublicKeyBS, Set[BlockHash]],
      isBookingBlock: Boolean
  ): F[Message.Block] =
    for {
      dag    <- DagStorage[F].getRepresentation
      parent <- dag.lookupUnsafe(mainParent)
      unsigned = BlockSummary()
        .withHeader(
          Block
            .Header()
            .withValidatorPublicKey(validatorId)
            .withParentHashes(List(mainParent))
            .withJustifications(
              for {
                kv <- justifications.toList
                h  <- kv._2.toList
              } yield Block.Justification(kv._1, h)
            )
            .withRoundId(roundId)
            .withKeyBlockHash(eraId)
            .withState(
              Block.GlobalState().withBonds(parent.blockSummary.getHeader.getState.bonds)
            )
        )
      hash   = ProtoUtil.protoHash(unsigned)
      signed = unsigned.withBlockHash(hash)

      msg <- Sync[F].fromTry(Message.fromBlockSummary(signed)).map(_.asInstanceOf[Message.Block])
      _   <- insert(msg)
    } yield msg
}
