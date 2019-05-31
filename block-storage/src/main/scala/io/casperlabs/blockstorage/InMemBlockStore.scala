package io.casperlabs.blockstorage

import cats.effect.Sync
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import cats.{Apply, Monad}
import io.casperlabs.blockstorage.BlockStore.{BlockHash, DeployHash, MeteredBlockStore}
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.storage.BlockMsgWithTransform

import scala.language.higherKinds

class InMemBlockStore[F[_]] private (
    implicit
    monadF: Monad[F],
    refF: Ref[F, Map[BlockHash, (BlockMsgWithTransform, BlockSummary)]],
    reverseIdxRefF: Ref[F, Map[DeployHash, Seq[BlockHash]]],
    lock: Semaphore[F],
    approvedBlockRef: Ref[F, Option[ApprovedBlock]]
) extends BlockStore[F] {

  def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    refF.get.map(_.get(blockHash).map(_._1))

  override def findBlockHash(p: BlockHash => Boolean): F[Option[BlockHash]] =
    refF.get.map(_.keys.find(p))

  def put(
      f: => (BlockHash, BlockMsgWithTransform)
  ): F[Unit] =
    lock.withPermit {
      refF
        .update(
          _ + (f._1 -> (f._2, f._2.toBlockSummary))
        ) *>
        reverseIdxRefF.update { m =>
          f._2.getBlockMessage.getBody.deploys.foldLeft(m) { (m, d) =>
            m.updated(
              d.getDeploy.deployHash,
              m.getOrElse(d.getDeploy.deployHash, Seq.empty[BlockHash]) :+ f._1
            )
          }
        }
    }

  def getApprovedBlock(): F[Option[ApprovedBlock]] =
    approvedBlockRef.get

  def putApprovedBlock(block: ApprovedBlock): F[Unit] =
    approvedBlockRef.set(Some(block))

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    refF.get.map(_.get(blockHash).map(_._2))

  override def findBlockHashesWithDeployhash(deployHash: BlockHash): F[Seq[BlockHash]] =
    reverseIdxRefF.get.map(_.getOrElse(deployHash, Seq.empty[BlockHash]).distinct)

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[Unit] =
    refF.update(_.empty)

  override def close(): F[Unit] =
    monadF.pure(())
}

object InMemBlockStore {
  def create[F[_]](
      implicit
      monadF: Monad[F],
      refF: Ref[F, Map[BlockHash, (BlockMsgWithTransform, BlockSummary)]],
      reverseIdxRefF: Ref[F, Map[DeployHash, Seq[BlockHash]]],
      approvedBlockRef: Ref[F, Option[ApprovedBlock]],
      lock: Semaphore[F],
      metricsF: Metrics[F]
  ): BlockStore[F] =
    new InMemBlockStore[F] with MeteredBlockStore[F] {
      override implicit val m: Metrics[F] = metricsF
      override implicit val ms: Source    = Metrics.Source(BlockStorageMetricsSource, "in-mem")
      override implicit val a: Apply[F]   = monadF
    }

  def emptyMapRef[F[_], V](
      implicit syncEv: Sync[F]
  ): F[Ref[F, Map[BlockHash, V]]] =
    Ref[F].of(Map.empty[BlockHash, V])

}
