package io.casperlabs.blockstorage

import cats.implicits._
import cats.{Applicative, Apply}
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.metrics.Metrics

import scala.language.higherKinds

trait BlockStore[F[_]] {
  import BlockStore.BlockHash

  def put(blockMessage: BlockMessage): F[Unit] =
    put((blockMessage.blockHash, blockMessage))

  def put(blockHash: BlockHash, blockMessage: BlockMessage): F[Unit] =
    put((blockHash, blockMessage))

  def get(blockHash: BlockHash): F[Option[BlockMessage]]

  def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMessage)]]

  def put(f: => (BlockHash, BlockMessage)): F[Unit]

  def apply(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[BlockMessage] =
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

    abstract override def get(blockHash: BlockHash): F[Option[BlockMessage]] =
      m.incrementCounter("get") *> super.get(blockHash)

    abstract override def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMessage)]] =
      m.incrementCounter("find") *> super.find(p)

    abstract override def put(f: => (BlockHash, BlockMessage)): F[Unit] =
      m.incrementCounter("put") *> super.put(f)
  }

  def apply[F[_]](implicit ev: BlockStore[F]): BlockStore[F] = ev

  type BlockHash = ByteString

}
