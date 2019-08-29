package io.casperlabs.storage.block

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.{Apply, Monad}
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.storage.block.BlockStorage.{
  BlockHash,
  BlockHashPrefix,
  DeployHash,
  MeteredBlockStorage
}
import io.casperlabs.storage.{BlockMsgWithTransform, BlockStorageMetricsSource}

import scala.language.higherKinds

class InMemBlockStorage[F[_]] private (
    implicit
    monadF: Monad[F],
    refF: Ref[F, Map[BlockHash, (BlockMsgWithTransform, BlockSummary)]],
    reverseIdxRefF: Ref[F, Map[DeployHash, Seq[BlockHash]]],
    approvedBlockRef: Ref[F, Option[ApprovedBlock]]
) extends BlockStorage[F] {

  def get(blockHashPrefix: BlockHashPrefix): F[Option[BlockMsgWithTransform]] =
    if (blockHashPrefix.size() == 32) {
      refF.get.map(_.get(blockHashPrefix).map(_._1))
    } else {
      refF.get.map(_.collectFirst {
        case (blockHash, (blockMsg, _)) if blockHash.startsWith(blockHashPrefix) =>
          blockMsg
      })
    }

  override def isEmpty: F[Boolean] = refF.get.map(_.isEmpty)

  def put(
      blockHash: BlockHash,
      blockMsgWithTransform: BlockMsgWithTransform
  ): F[Unit] =
    refF
      .update(
        _.updated(blockHash, (blockMsgWithTransform, blockMsgWithTransform.toBlockSummary))
      ) *>
      reverseIdxRefF.update { m =>
        blockMsgWithTransform.getBlockMessage.getBody.deploys.foldLeft(m) { (m, d) =>
          m.updated(
            d.getDeploy.deployHash,
            m.getOrElse(d.getDeploy.deployHash, Seq.empty[BlockHash]) :+ blockHash
          )
        }
      }

  def getApprovedBlock(): F[Option[ApprovedBlock]] =
    approvedBlockRef.get

  def putApprovedBlock(block: ApprovedBlock): F[Unit] =
    approvedBlockRef.set(Some(block))

  override def getBlockSummary(blockHashPrefix: BlockHashPrefix): F[Option[BlockSummary]] =
    if (blockHashPrefix.size() == 32) {
      refF.get.map(_.get(blockHashPrefix).map(_._2))
    } else {
      refF.get.map(_.collectFirst {
        case (blockHash, (_, blockSummary)) if blockHash.startsWith(blockHashPrefix) =>
          blockSummary
      })
    }

  override def findBlockHashesWithDeployhash(deployHash: BlockHash): F[Seq[BlockHash]] =
    reverseIdxRefF.get.map(_.getOrElse(deployHash, Seq.empty[BlockHash]).distinct)

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[Unit] =
    refF.update(_.empty)

  override def close(): F[Unit] =
    monadF.pure(())
}

object InMemBlockStorage {
  def create[F[_]](
      implicit
      monadF: Monad[F],
      refF: Ref[F, Map[BlockHash, (BlockMsgWithTransform, BlockSummary)]],
      reverseIdxRefF: Ref[F, Map[DeployHash, Seq[BlockHash]]],
      approvedBlockRef: Ref[F, Option[ApprovedBlock]],
      metricsF: Metrics[F]
  ): BlockStorage[F] =
    new InMemBlockStorage[F] with MeteredBlockStorage[F] {
      override implicit val m: Metrics[F] = metricsF
      override implicit val ms: Source    = Metrics.Source(BlockStorageMetricsSource, "in-mem")
      override implicit val a: Apply[F]   = monadF
    }

  def emptyMapRef[F[_], V](
      implicit syncEv: Sync[F]
  ): F[Ref[F, Map[BlockHash, V]]] =
    Ref[F].of(Map.empty[BlockHash, V])

  def empty[F[_]: Sync: Metrics] =
    for {
      blockMapRef      <- Ref[F].of(Map.empty[BlockHash, (BlockMsgWithTransform, BlockSummary)])
      deployMapRef     <- Ref[F].of(Map.empty[DeployHash, Seq[BlockHash]])
      approvedBlockRef <- Ref.of[F, Option[ApprovedBlock]](None)
      store            = create[F](Sync[F], blockMapRef, deployMapRef, approvedBlockRef, Metrics[F])
    } yield store
}
