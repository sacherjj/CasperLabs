package io.casperlabs.storage.block

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.storage.block.BlockStorage.{BlockHash, BlockHashPrefix, MeteredBlockStorage}
import io.casperlabs.storage.util.DoobieCodecs
import io.casperlabs.storage.{BlockMsgWithTransform, BlockStorageMetricsSource}

class SQLiteBlockStorage[F[_]: Bracket[?[_], Throwable]: Fs2Compiler](
    xa: Transactor[F],
    approvedBlockRef: Ref[F, Option[ApprovedBlock]]
) extends BlockStorage[F]
    with DoobieCodecs {

  override def get(blockHashPrefix: BlockHashPrefix): F[Option[BlockMsgWithTransform]] =
    (if (blockHashPrefix.size() == 32) {
       sql"""|SELECT data
             |FROM blocks
             |WHERE block_hash=$blockHashPrefix"""
     } else {
       val hex = Base16.encode(blockHashPrefix.toByteArray) ++ "%"
       sql"""|SELECT data
             |FROM blocks
             |WHERE block_hash_hex LIKE $hex"""
     }).stripMargin
      .query[BlockMsgWithTransform]
      .stream
      .head
      .compile
      .toList
      .transact(xa)
      .map(_.headOption)

  override def put(blockHash: BlockHash, blockMsg: BlockMsgWithTransform): F[Unit] = {
    val summary =
      BlockSummary(blockHash, blockMsg.getBlockMessage.header, blockMsg.getBlockMessage.signature).toByteString
    val validator = blockMsg.getBlockMessage.validatorPublicKey
    val rank      = blockMsg.getBlockMessage.rank
    val hex       = Base16.encode(blockHash.toByteArray)
    val transaction = for {
      _ <- sql"""|INSERT OR IGNORE INTO block_metadata
                 |(block_hash, block_hash_hex, validator, rank, data)
                 |VALUES ($blockHash, $hex, $validator, $rank, $summary)""".stripMargin.update.run
      _ <- sql"""|INSERT OR IGNORE INTO blocks
                 |(block_hash, block_hash_hex, data)
                 |VALUES ($blockHash, $hex, $blockMsg)""".stripMargin.update.run
    } yield ()

    transaction.transact(xa)
  }

  override def getApprovedBlock(): F[Option[ApprovedBlock]] = approvedBlockRef.get

  override def putApprovedBlock(block: ApprovedBlock): F[Unit] = approvedBlockRef.set(block.some)

  override def getBlockSummary(blockHashPrefix: BlockHashPrefix): F[Option[BlockSummary]] =
    (if (blockHashPrefix.size() == 32) {
       sql"""|SELECT data
             |FROM block_metadata
             |WHERE block_hash=$blockHashPrefix"""
     } else {
       val hex = Base16.encode(blockHashPrefix.toByteArray) + "%"
       sql"""|SELECT data
             |FROM block_metadata
             |WHERE block_hash_hex LIKE $hex"""
     }).stripMargin
      .query[BlockSummary]
      .stream
      .head
      .compile
      .toList
      .transact(xa)
      .map(_.headOption)

  override def isEmpty: F[Boolean] =
    sql"SELECT COUNT(*) FROM blocks".query[Long].unique.map(_ == 0L).transact(xa)

  override def findBlockHashesWithDeployhash(deployHash: ByteString): F[Seq[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM deploy_process_results
          |WHERE deploy_hash=$deployHash""".stripMargin.query[BlockHash].to[Seq].transact(xa)

  override def checkpoint(): F[Unit] = ().pure[F]

  override def clear(): F[Unit] =
    sql"DELETE FROM blocks".update.run.void.transact(xa)

  override def close(): F[Unit] = ().pure[F]
}

object SQLiteBlockStorage {
  def apply[F[_]](
      implicit xa: Transactor[F],
      metricsF: Metrics[F],
      syncF: Sync[F],
      fs2Compiler: Fs2Compiler[F]
  ): Resource[F, BlockStorage[F]] = Resource.make(create[F])(_.close())

  def create[F[_]](
      implicit xa: Transactor[F],
      metricsF: Metrics[F],
      syncF: Sync[F],
      fs2Compiler: Fs2Compiler[F]
  ): F[BlockStorage[F]] =
    for {
      ref <- Ref.of[F, Option[ApprovedBlock]](None)
      blockStorage <- Sync[F].delay(new SQLiteBlockStorage[F](xa, ref) with MeteredBlockStorage[F] {
                       override implicit val m: Metrics[F] = metricsF
                       override implicit val ms: Source =
                         Metrics.Source(BlockStorageMetricsSource, "sqlite")
                       override implicit val a: Apply[F] = syncF
                     })
    } yield blockStorage: BlockStorage[F]
}
