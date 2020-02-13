package io.casperlabs.casper.helper

import cats.data.NonEmptyList
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
    estimatorFunc: NonEmptyList[BlockHash]
) extends MultiParentCasper[F] {

  def store: Map[BlockHash, BlockMsgWithTransform] = blockStorage.toMap

  def addBlock(b: Block): F[BlockStatus] =
    for {
      _ <- Sync[F].delay(
            blockStorage
              .update(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty))
          )
      _ <- BlockStorage[F]
            .put(b.blockHash, BlockMsgWithTransform(Some(b), Seq.empty))
    } yield BlockStatus.valid
  def contains(b: Block): F[Boolean] = false.pure[F]
  def estimator(
      dag: DagRepresentation[F],
      lfbHash: ByteString,
      latestMessageHashes: Map[Validator, Set[ByteString]],
      equivocators: Set[Validator]
  ): F[NonEmptyList[BlockHash]] =
    estimatorFunc.pure[F]
  def createMessage(canCreateBallot: Boolean): F[CreateBlockStatus] =
    CreateBlockStatus.noNewDeploys.pure[F]
  def dag: F[DagRepresentation[F]] = DagStorage[F].getRepresentation
  def lastFinalizedBlock: F[Block] = Block().pure[F]
  def fetchDependencies: F[Unit]   = ().pure[F]
  def faultToleranceThreshold      = 0f
}

object NoOpsCasperEffect {
  def apply[F[_]: Sync: BlockStorage: DagStorage](
      blockStorage: Map[BlockHash, BlockMsgWithTransform] = Map.empty,
      estimatorFunc: NonEmptyList[BlockHash] = NonEmptyList.one(Block().blockHash)
  ): F[NoOpsCasperEffect[F]] =
    for {
      _ <- blockStorage.toList.traverse_ {
            case (blockHash, block) => BlockStorage[F].put(blockHash, block)
          }
    } yield new NoOpsCasperEffect[F](MutableMap(blockStorage.toSeq: _*), estimatorFunc)
  def apply[F[_]: Sync: BlockStorage: DagStorage](): F[NoOpsCasperEffect[F]] =
    apply(
      Map(Block().blockHash -> BlockMsgWithTransform().withBlockMessage(Block())),
      NonEmptyList.one(Block().blockHash)
    )
  def apply[F[_]: Sync: BlockStorage: DagStorage](
      blockStorage: Map[BlockHash, BlockMsgWithTransform]
  ): F[NoOpsCasperEffect[F]] =
    apply(blockStorage, NonEmptyList.one(Block().blockHash))
}
