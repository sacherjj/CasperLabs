package io.casperlabs.casper.helper

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.{BlockStatus, CreateBlockStatus, MultiParentCasper, ValidatorIdentity}
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.casper.DeployBuffer
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.storage.BlockMsgWithTransform

import scala.collection.mutable.{Map => MutableMap}

class NoOpsCasperEffect[F[_]: Sync: BlockStore: BlockDagStorage] private (
    private val blockStore: MutableMap[BlockHash, BlockMsgWithTransform],
    estimatorFunc: IndexedSeq[BlockHash]
) extends MultiParentCasper[F] {

  def store: Map[BlockHash, BlockMsgWithTransform] = blockStore.toMap

  def addBlock(b: Block): F[BlockStatus] =
    for {
      _ <- Sync[F].delay(
            blockStore
              .update(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty[TransformEntry]))
          )
      _ <- BlockStore[F].put(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty[TransformEntry]))
    } yield BlockStatus.valid
  def contains(b: Block): F[Boolean]                = false.pure[F]
  def deploy(r: Deploy): F[Either[Throwable, Unit]] = Applicative[F].pure(Right(()))
  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockHash]] =
    estimatorFunc.pure[F]
  def createBlock: F[CreateBlockStatus]                               = CreateBlockStatus.noNewDeploys.pure[F]
  def blockDag: F[BlockDagRepresentation[F]]                          = BlockDagStorage[F].getRepresentation
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] = 0f.pure[F]
  def lastFinalizedBlock: F[Block]                                    = Block().pure[F]
  def fetchDependencies: F[Unit]                                      = ().pure[F]
  def bufferedDeploys: F[DeployBuffer]                                = DeployBuffer.empty.pure[F]
  def faultToleranceThreshold                                         = 0f
}

object NoOpsCasperEffect {
  def apply[F[_]: Sync: BlockStore: BlockDagStorage](
      blockStore: Map[BlockHash, BlockMsgWithTransform] = Map.empty,
      estimatorFunc: IndexedSeq[BlockHash] = Vector(Block().blockHash)
  ): F[NoOpsCasperEffect[F]] =
    for {
      _ <- blockStore.toList.traverse_ {
            case (blockHash, block) => BlockStore[F].put(blockHash, block)
          }
    } yield new NoOpsCasperEffect[F](MutableMap(blockStore.toSeq: _*), estimatorFunc)
  def apply[F[_]: Sync: BlockStore: BlockDagStorage](): F[NoOpsCasperEffect[F]] =
    apply(
      Map(Block().blockHash -> BlockMsgWithTransform().withBlockMessage(Block())),
      Vector(Block().blockHash)
    )
  def apply[F[_]: Sync: BlockStore: BlockDagStorage](
      blockStore: Map[BlockHash, BlockMsgWithTransform]
  ): F[NoOpsCasperEffect[F]] =
    apply(blockStore, Vector(Block().blockHash))
}
