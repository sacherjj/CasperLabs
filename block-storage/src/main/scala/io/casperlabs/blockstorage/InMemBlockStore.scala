package io.casperlabs.blockstorage

import cats._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.StorageError.StorageIOErr
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source

import scala.language.higherKinds

class InMemBlockStore[F[_]] private (
    implicit
    monadF: Monad[F],
    refF: Ref[F, Map[BlockHash, BlockMessage]]
) extends BlockStore[F] {

  def get(blockHash: BlockHash): F[Option[BlockMessage]] =
    refF.get.map(_.get(blockHash))

  override def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMessage)]] =
    refF.get.map(_.filterKeys(p).toSeq)

  def put(f: => (BlockHash, BlockMessage)): F[StorageIOErr[Unit]] =
    refF.update(_ + f) map Right.apply

  def checkpoint(): F[StorageIOErr[Unit]] =
    ().asRight[StorageIOError].pure[F]

  def clear(): F[StorageIOErr[Unit]] =
    refF.update(_.empty) map Right.apply

  override def close(): F[StorageIOErr[Unit]] =
    monadF.pure(Right(()))
}

object InMemBlockStore {
  def create[F[_]](
      implicit
      monadF: Monad[F],
      refF: Ref[F, Map[BlockHash, BlockMessage]],
      metricsF: Metrics[F]
  ): BlockStore[F] =
    new InMemBlockStore[F] with BlockStore.WithMetrics[F] {
      override implicit val m: Metrics[F] = metricsF
      override implicit val ms: Source    = Metrics.Source(BlockStorageMetricsSource, "in-mem")
      override implicit val a: Apply[F]   = monadF
    }

  def createWithId: BlockStore[Id] = {
    import io.casperlabs.catscontrib.effect.implicits._
    import io.casperlabs.metrics.Metrics.MetricsNOP
    val refId                         = emptyMapRef[Id](syncId)
    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()(syncId)
    InMemBlockStore.create(syncId, refId, metrics)
  }

  def emptyMapRef[F[_]](implicit syncEv: Sync[F]): F[Ref[F, Map[BlockHash, BlockMessage]]] =
    Ref[F].of(Map.empty[BlockHash, BlockMessage])

}
