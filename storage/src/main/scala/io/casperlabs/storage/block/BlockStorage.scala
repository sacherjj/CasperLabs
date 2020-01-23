package io.casperlabs.storage.block

import cats.Applicative
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metered
import io.casperlabs.storage.{BlockHash, BlockMsgWithTransform, DeployHash}
import scala.language.higherKinds
import simulacrum.typeclass

@typeclass
trait BlockStorageWriter[F[_]] {
  def put(blockHash: BlockHash, blockMsgWithTransform: BlockMsgWithTransform): F[Unit]

  def put(
      blockMsgWithTransform: BlockMsgWithTransform
  )(implicit applicative: Applicative[F]): F[Unit] =
    blockMsgWithTransform.blockMessage.fold(().pure[F])(
      b => put(b.blockHash, blockMsgWithTransform)
    )

  def put(
      blockHash: BlockHash,
      blockMessage: Block,
      transforms: Seq[TransformEntry]
  ): F[Unit] =
    put(blockHash, BlockMsgWithTransform(Some(blockMessage), transforms))
}

@typeclass
trait BlockStorageReader[F[_]] extends BlockStorageWriter[F] {
  def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]]

  def getByPrefix(blockHashPrefix: String): F[Option[BlockMsgWithTransform]]

  def isEmpty: F[Boolean]

  def apply(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[BlockMsgWithTransform] =
    get(blockHash).map(_.get)

  def contains(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[Boolean] =
    get(blockHash).map(_.isDefined)

  def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]]

  def getBlockInfo(blockHash: BlockHash): F[Option[BlockInfo]]

  def getBlockInfoByPrefix(blockHashPrefix: String): F[Option[BlockInfo]]

  /**
    * Note: if there are no blocks for the specified deployHash,
    * Result.get(deployHash) returns Some(Set.empty[BlockHash]) instead of None
    */
  def findBlockHashesWithDeployHashes(
      deployHashes: List[DeployHash]
  ): F[Map[DeployHash, Set[BlockHash]]]

  def getBlockMessage(
      blockHash: BlockHash
  )(implicit applicative: Applicative[F]): F[Option[Block]] =
    get(blockHash).map(_.flatMap(_.blockMessage))

  def getTransforms(
      blockHash: BlockHash
  )(implicit applicative: Applicative[F]): F[Option[Seq[TransformEntry]]] =
    get(blockHash).map(_.map(_.transformEntry))

  def getUnsafe(hash: BlockHash)(implicit MT: MonadThrowable[F]): F[BlockMsgWithTransform] =
    unsafe(hash, get)

  def getBlockUnsafe(hash: BlockHash)(implicit MT: MonadThrowable[F]): F[Block] =
    getUnsafe(hash).map(_.getBlockMessage)

  def getBlockSummaryUnsafe(hash: BlockHash)(implicit MT: MonadThrowable[F]): F[BlockSummary] =
    unsafe(hash, getBlockSummary)

  private def unsafe[A](hash: BlockHash, f: BlockHash => F[Option[A]])(
      implicit MT: MonadThrowable[F]
  ): F[A] =
    f(hash) flatMap { maybeA =>
      MT.fromOption(
        maybeA,
        new NoSuchElementException(
          s"BlockStorage is missing hash ${Base16.encode(hash.toByteArray)}"
        )
      )
    }
}

@typeclass
trait BlockStorage[F[_]] extends BlockStorageWriter[F] with BlockStorageReader[F] {

  def checkpoint(): F[Unit]

  def clear(): F[Unit]

  def close(): F[Unit]
}

object BlockStorage {
  type BlockMessage = Block

  trait MeteredBlockStorage[F[_]] extends BlockStorage[F] with Metered[F] {

    abstract override def get(
        blockHash: BlockHash
    ): F[Option[BlockMsgWithTransform]] =
      incAndMeasure("get", super.get(blockHash))

    abstract override def getByPrefix(
        blockHashPrefix: String
    ): F[Option[BlockMsgWithTransform]] =
      incAndMeasure("getByPrefix", super.getByPrefix(blockHashPrefix))

    abstract override def isEmpty: F[Boolean] =
      incAndMeasure("isEmpty", super.isEmpty)

    abstract override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
      incAndMeasure("getBlockSummary", super.getBlockSummary(blockHash))

    abstract override def getBlockInfo(blockHash: BlockHash): F[Option[BlockInfo]] =
      incAndMeasure("getBlockInfo", super.getBlockInfo(blockHash))

    abstract override def getBlockInfoByPrefix(
        blockHashPrefix: String
    ): F[Option[BlockInfo]] =
      incAndMeasure("getSummaryByPrefix", super.getBlockInfoByPrefix(blockHashPrefix))

    abstract override def put(
        blockHash: BlockHash,
        blockMsgWithTransform: BlockMsgWithTransform
    ): F[Unit] =
      incAndMeasure("put", super.put(blockHash, blockMsgWithTransform))

    abstract override def checkpoint(): F[Unit] =
      incAndMeasure("checkpoint", super.checkpoint())

    abstract override def contains(
        blockHash: BlockHash
    )(implicit applicativeF: Applicative[F]): F[Boolean] =
      incAndMeasure("contains", super.contains(blockHash))

    abstract override def findBlockHashesWithDeployHashes(
        deployHashes: List[DeployHash]
    ): F[Map[DeployHash, Set[BlockHash]]] =
      incAndMeasure(
        "findBlockHashesWithDeployHashes",
        super.findBlockHashesWithDeployHashes(deployHashes)
      )
  }
}
