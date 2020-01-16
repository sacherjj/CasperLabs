package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond}
import io.casperlabs.casper.highway.{MessageProducer, Ticks}
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.storage.BlockHash
import io.casperlabs.models.Message

class MockMessageProducer[F[_]: Applicative](
    val validatorId: PublicKeyBS
) extends MessageProducer[F] {

  override def ballot(
      eraId: BlockHash,
      roundId: Ticks,
      target: ByteString,
      justifications: Map[PublicKeyBS, Set[BlockHash]]
  ): F[Message.Ballot] =
    BlockSummary()
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
      )
      .pure[F]
      .map(Message.fromBlockSummary(_).get.asInstanceOf[Message.Ballot])

  override def block(
      eraId: ByteString,
      roundId: Ticks,
      mainParent: ByteString,
      justifications: Map[PublicKeyBS, Set[BlockHash]],
      isBookingBlock: Boolean
  ): F[Message.Block] =
    BlockSummary()
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
      )
      .pure[F]
      .map(Message.fromBlockSummary(_).get.asInstanceOf[Message.Block])
}
