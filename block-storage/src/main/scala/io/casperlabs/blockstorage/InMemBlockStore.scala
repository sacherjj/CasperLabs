package io.casperlabs.blockstorage

import cats._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore.{BlockHash, MeteredBlockStore}
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
    approvedBlockRef: Ref[F, Option[ApprovedBlock]]
) extends BlockStore[F] {

  def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    refF.get.map(_.get(blockHash).map(_._1))

  override def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMsgWithTransform)]] =
    refF.get.map(_.filterKeys(p).map {
      case (k, (b, s)) => (k, b)
    }.toSeq)

  def put(f: => (BlockHash, BlockMsgWithTransform)): F[Unit] =
    refF.update(
      _ + (f._1 -> (f._2, f._2.toBlockSummary))
    )

  def getApprovedBlock(): F[Option[ApprovedBlock]] =
    approvedBlockRef.get

  def putApprovedBlock(block: ApprovedBlock): F[Unit] =
    approvedBlockRef.set(Some(block))

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    refF.get.map(_.get(blockHash).map(_._2))

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
      approvedBlockRef: Ref[F, Option[ApprovedBlock]],
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
