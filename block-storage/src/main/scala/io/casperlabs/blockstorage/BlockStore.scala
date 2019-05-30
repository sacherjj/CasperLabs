package io.casperlabs.blockstorage

import cats.implicits._
import cats.{Applicative, Apply}
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metered
import io.casperlabs.metrics.implicits._
import io.casperlabs.storage.BlockMsgWithTransform

import scala.language.higherKinds

trait BlockStore[F[_]] {
  import BlockStore.{BlockHash, BlockMessage}

  def put(
      blockMsgWithTransform: BlockMsgWithTransform
  )(implicit applicative: Applicative[F]): F[Unit] =
    blockMsgWithTransform.blockMessage.fold(().pure[F])(
      b => put((b.blockHash, blockMsgWithTransform))
    )

  def put(
      blockHash: BlockHash,
      blockMessage: BlockMessage,
      transforms: Seq[TransformEntry]
  ): F[Unit] =
    put((blockHash, BlockMsgWithTransform(Some(blockMessage), transforms)))

  def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]]

  def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMsgWithTransform)]]

  def put(f: => (BlockHash, BlockMsgWithTransform)): F[Unit]

  def apply(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[BlockMsgWithTransform] =
    get(blockHash).map(_.get)

  def contains(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[Boolean] =
    get(blockHash).map(_.isDefined)

  def getApprovedBlock(): F[Option[ApprovedBlock]]

  def putApprovedBlock(block: ApprovedBlock): F[Unit]

  def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]]

  def findBlockHashesWithDeployhash(deployHash: ByteString): F[Seq[BlockHash]]

  def checkpoint(): F[Unit]

  def clear(): F[Unit]

  def close(): F[Unit]
}

object BlockStore {
  type BlockMessage = Block

  trait MeteredBlockStore[F[_]] extends BlockStore[F] with Metered[F] {

    abstract override def get(
        blockHash: BlockHash
    ): F[Option[BlockMsgWithTransform]] =
      incAndMeasure("get", super.get(blockHash))

    abstract override def find(
        p: BlockHash => Boolean
    ): F[Seq[(BlockHash, BlockMsgWithTransform)]] =
      incAndMeasure("find", super.find(p))

    abstract override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
      incAndMeasure("getBlockSummary", super.getBlockSummary(blockHash))

    abstract override def put(f: => (BlockHash, BlockMsgWithTransform)): F[Unit] =
      incAndMeasure("put", super.put(f))

    abstract override def checkpoint(): F[Unit] =
      super.checkpoint().timer("checkpoint-time")

    abstract override def contains(
        blockHash: BlockHash
    )(implicit applicativeF: Applicative[F]): F[Boolean] =
      super.contains(blockHash).timer("contains-time")
  }

  implicit class RichBlockStore[F[_]](blockStore: BlockStore[F]) {
    def getBlockMessage(
        blockHash: BlockHash
    )(implicit applicative: Applicative[F]): F[Option[BlockMessage]] =
      blockStore.get(blockHash).map(it => it.flatMap(_.blockMessage))

    def getTransforms(
        blockHash: BlockHash
    )(implicit applicative: Applicative[F]): F[Option[Seq[TransformEntry]]] =
      blockStore.get(blockHash).map(_.map(_.transformEntry))
  }
  def apply[F[_]](implicit ev: BlockStore[F]): BlockStore[F] = ev

  type BlockHash  = ByteString
  type DeployHash = ByteString

}
