package io.casperlabs.casper.helper

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.casper.{BlockStatus, CreateBlockStatus, MultiParentCasper}
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.models.Weight
import io.casperlabs.storage._
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._

import scala.collection.mutable.{Map => MutableMap}

class NoOpsCasperEffect[F[_]: Sync: BlockStorage: DagStorage] private (
    private val blockStorage: MutableMap[BlockHash, BlockMsgWithTransform],
    estimatorFunc: List[BlockHash]
) extends MultiParentCasper[F] {

  def store: Map[BlockHash, BlockMsgWithTransform] = blockStorage.toMap

  def addBlock(b: Block): F[BlockStatus] =
    for {
      _ <- Sync[F].delay(
            blockStorage
              .update(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty[TransformEntry]))
          )
      _ <- BlockStorage[F]
            .put(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty[TransformEntry]))
    } yield BlockStatus.valid
  def contains(b: Block): F[Boolean]                = false.pure[F]
  def deploy(r: Deploy): F[Either[Throwable, Unit]] = Applicative[F].pure(Right(()))
  def estimator(
      dag: DagRepresentation[F],
      latestMessageHashes: Map[ByteString, Set[ByteString]]
  ): F[List[BlockHash]] =
    estimatorFunc.pure[F]
  def createBlock: F[CreateBlockStatus]                                 = CreateBlockStatus.noNewDeploys.pure[F]
  def dag: F[DagRepresentation[F]]                                      = DagStorage[F].getRepresentation
  def normalizedInitialFault(weights: Map[Validator, Weight]): F[Float] = 0f.pure[F]
  def lastFinalizedBlock: F[Block]                                      = Block().pure[F]
  def fetchDependencies: F[Unit]                                        = ().pure[F]
  def faultToleranceThreshold                                           = 0f
}

object NoOpsCasperEffect {
  def apply[F[_]: Sync: BlockStorage: DagStorage](
      blockStorage: Map[BlockHash, BlockMsgWithTransform] = Map.empty,
      estimatorFunc: List[BlockHash] = List(Block().blockHash)
  ): F[NoOpsCasperEffect[F]] =
    for {
      _ <- blockStorage.toList.traverse_ {
            case (blockHash, block) => BlockStorage[F].put(blockHash, block)
          }
    } yield new NoOpsCasperEffect[F](MutableMap(blockStorage.toSeq: _*), estimatorFunc)
  def apply[F[_]: Sync: BlockStorage: DagStorage](): F[NoOpsCasperEffect[F]] =
    apply(
      Map(Block().blockHash -> BlockMsgWithTransform().withBlockMessage(Block())),
      List(Block().blockHash)
    )
  def apply[F[_]: Sync: BlockStorage: DagStorage](
      blockStorage: Map[BlockHash, BlockMsgWithTransform]
  ): F[NoOpsCasperEffect[F]] =
    apply(blockStorage, List(Block().blockHash))
}
