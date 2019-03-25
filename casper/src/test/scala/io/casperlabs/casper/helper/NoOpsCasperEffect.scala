package io.casperlabs.casper.helper

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.protocol.{BlockMessage, DeployData}
import io.casperlabs.casper.{BlockStatus, CreateBlockStatus, MultiParentCasper}
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.storage.BlockMsgWithTransform

import scala.collection.mutable.{Map => MutableMap}

class NoOpsCasperEffect[F[_]: Sync: BlockStore: BlockDagStorage] private (
    private val blockStore: MutableMap[BlockHash, BlockMsgWithTransform],
    estimatorFunc: IndexedSeq[BlockMessage]
) extends MultiParentCasper[F] {

  def store: Map[BlockHash, BlockMsgWithTransform] = blockStore.toMap

  def addBlock(
      b: BlockMessage,
      handleDoppelganger: (BlockMessage, Validator) => F[Unit]
  ): F[BlockStatus] =
    for {
      _ <- Sync[F].delay(
            blockStore
              .update(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty[TransformEntry]))
          )
      _ <- BlockStore[F].put(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty[TransformEntry]))
    } yield BlockStatus.valid
  def contains(b: BlockMessage): F[Boolean]             = false.pure[F]
  def deploy(r: DeployData): F[Either[Throwable, Unit]] = Applicative[F].pure(Right(()))
  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockMessage]] =
    estimatorFunc.pure[F]
  def createBlock: F[CreateBlockStatus]                               = CreateBlockStatus.noNewDeploys.pure[F]
  def blockDag: F[BlockDagRepresentation[F]]                          = BlockDagStorage[F].getRepresentation
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] = 0f.pure[F]
  def lastFinalizedBlock: F[BlockMessage]                             = BlockMessage().pure[F]
  def storageContents(hash: BlockHash): F[String]                     = "".pure[F]
  def fetchDependencies: F[Unit]                                      = ().pure[F]
}

object NoOpsCasperEffect {
  def apply[F[_]: Sync: BlockStore: BlockDagStorage](
      blockStore: Map[BlockHash, BlockMsgWithTransform] = Map.empty,
      estimatorFunc: IndexedSeq[BlockMessage] = Vector(BlockMessage())
  ): F[NoOpsCasperEffect[F]] =
    for {
      _ <- blockStore.toList.traverse_ {
            case (blockHash, block) => BlockStore[F].put(blockHash, block)
          }
    } yield new NoOpsCasperEffect[F](MutableMap(blockStore.toSeq: _*), estimatorFunc)
  def apply[F[_]: Sync: BlockStore: BlockDagStorage](): F[NoOpsCasperEffect[F]] =
    apply(Map.empty, Vector(BlockMessage()))
  def apply[F[_]: Sync: BlockStore: BlockDagStorage](
      blockStore: Map[BlockHash, BlockMsgWithTransform]
  ): F[NoOpsCasperEffect[F]] =
    apply(blockStore, Vector(BlockMessage()))
}
