package io.casperlabs.blockstorage

import cats.implicits._
import cats.{Applicative, Apply}
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.storage.BlockMsgWithTransform

import scala.language.higherKinds

trait BlockStore[F[_]] {
  import BlockStore.BlockHash

  def put(blockMsgWithTransform: BlockMsgWithTransform)(
      implicit applicative: Applicative[F]): F[Unit] =
    blockMsgWithTransform.blockMessage.fold(().pure[F])(b =>
      put((b.blockHash, blockMsgWithTransform)))

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

  def checkpoint(): F[Unit]

  def clear(): F[Unit]

  def close(): F[Unit]
}

object BlockStore {
  trait MeteredBlockStore[F[_]] extends BlockStore[F] {
    implicit val m: Metrics[F]
    implicit val ms: Metrics.Source
    implicit val a: Apply[F]

    abstract override def get(
        blockHash: BlockHash
    ): F[Option[BlockMsgWithTransform]] =
      m.incrementCounter("get") *> super.get(blockHash).timer("get-time")

    abstract override def find(
        p: BlockHash => Boolean
    ): F[Seq[(BlockHash, BlockMsgWithTransform)]] =
      m.incrementCounter("find") *> super.find(p).timer("find-time")

    abstract override def put(f: => (BlockHash, BlockMsgWithTransform)): F[Unit] =
      m.incrementCounter("put") *> super.put(f).timer("put-time")

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
  }
  def apply[F[_]](implicit ev: BlockStore[F]): BlockStore[F] = ev

  type BlockHash = ByteString

}
