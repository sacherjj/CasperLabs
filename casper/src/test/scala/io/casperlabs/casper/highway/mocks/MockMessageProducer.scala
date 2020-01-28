package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.syntax.show
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
import scala.util.control.NonFatal

class MockMessageProducer[F[_]: Sync: BlockStorageWriter: DagStorage](
    val validatorId: PublicKeyBS
) extends MessageProducer[F] {
  import io.casperlabs.casper.highway.`Show[BlockHash]`

  private def insert(message: Message): F[Unit] = {
    val summary = message.blockSummary
    for {
      _ <- BlockStorageWriter[F].put(
            BlockMsgWithTransform().withBlockMessage(
              Block(
                blockHash = summary.blockHash,
                header = summary.header,
                signature = summary.signature
              )
            )
          )
      // Check that we got a version of the storage that maintains the DAG store as well.
      dag <- DagStorage[F].getRepresentation
      msg <- dag.lookup(message.messageHash)
      _ = assert(
        msg.isDefined,
        s"Storing block ${message.messageHash.show} did not update the DAG storage!"
      )
    } yield ()
  }

  private def withParent[A <: Message](
      parentBlockHash: BlockHash
  )(f: Message => F[A]): F[A] =
    for {
      dag <- DagStorage[F].getRepresentation
      parent <- dag.lookupUnsafe(parentBlockHash).recoverWith {
                 case NonFatal(ex) =>
                   Sync[F].raiseError(
                     new IllegalStateException(
                       s"Couldn't look up parent in MockMessageProducer: $ex"
                     )
                   )
               }
      child <- f(parent)
      _     <- insert(child)
    } yield child

  override def ballot(
      keyBlockHash: BlockHash,
      roundId: Ticks,
      target: ByteString,
      justifications: Map[PublicKeyBS, Set[BlockHash]]
  ): F[Message.Ballot] = withParent(target) { parent =>
    val unsigned = BlockSummary()
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
          .withKeyBlockHash(keyBlockHash)
          .withState(
            Block.GlobalState().withBonds(parent.blockSummary.getHeader.getState.bonds)
          )
      )
    val hash   = ProtoUtil.protoHash(unsigned)
    val signed = unsigned.withBlockHash(hash)

    Sync[F]
      .fromTry(Message.fromBlockSummary(signed))
      .map(_.asInstanceOf[Message.Ballot])
  }

  override def block(
      keyBlockHash: ByteString,
      roundId: Ticks,
      mainParent: ByteString,
      justifications: Map[PublicKeyBS, Set[BlockHash]],
      isBookingBlock: Boolean
  ): F[Message.Block] =
    withParent(mainParent) { parent =>
      val unsigned = BlockSummary()
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
            .withKeyBlockHash(keyBlockHash)
            .withState(
              Block.GlobalState().withBonds(parent.blockSummary.getHeader.getState.bonds)
            )
        )
      val hash   = ProtoUtil.protoHash(unsigned)
      val signed = unsigned.withBlockHash(hash)

      Sync[F]
        .fromTry(Message.fromBlockSummary(signed))
        .map(_.asInstanceOf[Message.Block])
    }
}
