package io.casperlabs.storage.block

import cats.Applicative
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metered
import io.casperlabs.storage.BlockMsgWithTransform

import scala.language.higherKinds

trait BlockStorage[F[_]] {
  import BlockStorage.{BlockHash, BlockMessage}

  def put(
      blockMsgWithTransform: BlockMsgWithTransform
  )(implicit applicative: Applicative[F]): F[Unit] =
    blockMsgWithTransform.blockMessage.fold(().pure[F])(
      b => put(b.blockHash, blockMsgWithTransform)
    )

  def put(
      blockHash: BlockHash,
      blockMessage: BlockMessage,
      transforms: Seq[TransformEntry]
  ): F[Unit] =
    put(blockHash, BlockMsgWithTransform(Some(blockMessage), transforms))

  def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]]

  def getByPrefix(blockHashPrefix: String): F[Option[BlockMsgWithTransform]]

  def isEmpty: F[Boolean]

  def put(blockHash: BlockHash, blockMsgWithTransform: BlockMsgWithTransform): F[Unit]

  def apply(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[BlockMsgWithTransform] =
    get(blockHash).map(_.get)

  def contains(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[Boolean] =
    get(blockHash).map(_.isDefined)

  def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]]

  def getSummaryByPrefix(blockHashPrefix: String): F[Option[BlockSummary]]

  def findBlockHashesWithDeployHash(deployHash: ByteString): F[Seq[BlockHash]]

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

    abstract override def getSummaryByPrefix(
        blockHashPrefix: String
    ): F[Option[BlockSummary]] =
      incAndMeasure("getSummaryByPrefix", super.getSummaryByPrefix(blockHashPrefix))

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

    abstract override def findBlockHashesWithDeployHash(deployHash: BlockHash): F[Seq[BlockHash]] =
      incAndMeasure(
        "findBlockHashesWithDeployHash",
        super.findBlockHashesWithDeployHash(deployHash)
      )
  }

  implicit class RichBlockStorage[F[_]](blockStorage: BlockStorage[F]) {
    def getBlockMessage(
        blockHash: BlockHash
    )(implicit applicative: Applicative[F]): F[Option[BlockMessage]] =
      blockStorage.get(blockHash).map(it => it.flatMap(_.blockMessage))

    def getTransforms(
        blockHash: BlockHash
    )(implicit applicative: Applicative[F]): F[Option[Seq[TransformEntry]]] =
      blockStorage.get(blockHash).map(_.map(_.transformEntry))
  }
  def apply[F[_]](implicit ev: BlockStorage[F]): BlockStorage[F] = ev

  type BlockHash  = ByteString
  type DeployHash = ByteString
}
