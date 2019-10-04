package io.casperlabs.storage.block

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.storage.block.BlockStorage.{BlockHash, MeteredBlockStorage}
import io.casperlabs.storage.util.DoobieCodecs
import io.casperlabs.storage.{BlockMsgWithTransform, BlockStorageMetricsSource}

class SQLiteBlockStorage[F[_]: Bracket[?[_], Throwable]: Fs2Compiler](
    xa: Transactor[F]
) extends BlockStorage[F]
    with DoobieCodecs {

  override def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    get(sql"""|SELECT block_hash, data
              |FROM block_metadata
              |WHERE block_hash=$blockHash""".stripMargin.query[(BlockHash, BlockSummary)].option)

  private def get(
      initial: ConnectionIO[Option[(BlockHash, BlockSummary)]]
  ): F[Option[BlockMsgWithTransform]] = {
    def createTransaction(blockHash: BlockHash, blockSummary: BlockSummary) =
      for {
        body <- sql"""|SELECT d.data, dpr.deploy_position, dpr.cost, dpr.execution_error_message
                      |FROM deploy_process_results dpr
                      |INNER JOIN deploys d
                      |ON dpr.deploy_hash=d.hash
                      |WHERE dpr.block_hash=$blockHash
                      |ORDER BY dpr.deploy_position""".stripMargin
                 .query[(Deploy, Int, Long, Option[String])]
                 .to[List]
                 .map { blockBodyData =>
                   val processedDeploys = blockBodyData.map {
                     case (deploy, _, cost, maybeError) =>
                       ProcessedDeploy(
                         deploy.some,
                         cost,
                         isError = maybeError.nonEmpty,
                         maybeError.getOrElse("")
                       )
                   }
                   Block.Body(processedDeploys)
                 }
        transforms <- sql"""|SELECT data
                            |FROM transforms
                            |WHERE block_hash=$blockHash""".stripMargin
                       .query[TransformEntry]
                       .to[List]
      } yield BlockMsgWithTransform(
        Block(blockSummary.blockHash, blockSummary.header, body.some, blockSummary.signature).some,
        transforms
      ).some

    val transaction = initial.flatMap(_.fold(none[BlockMsgWithTransform].pure[ConnectionIO]) {
      case (blockHash, blockSummary) => createTransaction(blockHash, blockSummary)
    })
    transaction.transact(xa)
  }

  override def getByPrefix(blockHashPrefix: String): F[Option[BlockMsgWithTransform]] = {
    def query(lowerBound: Array[Byte], upperBound: Array[Byte]) =
      get(
        sql"""|SELECT block_hash, data
              |FROM block_metadata
              |WHERE block_hash>=$lowerBound AND block_hash<=$upperBound
              |LIMIT 1""".stripMargin
          .query[(BlockHash, BlockSummary)]
          .option
      )

    getByPrefix[BlockMsgWithTransform](
      blockHashPrefix,
      get,
      (lowerBound, upperBound) => query(lowerBound, upperBound)
    )
  }

  override def getSummaryByPrefix(blockHashPrefix: String): F[Option[BlockSummary]] = {
    def query(lowerBound: Array[Byte], upperBound: Array[Byte]) =
      sql"""|SELECT data
            |FROM block_metadata
            |WHERE block_hash>=$lowerBound AND block_hash<=$upperBound
            |LIMIT 1""".stripMargin
        .query[BlockSummary]
        .option
        .transact(xa)

    getByPrefix[BlockSummary](
      blockHashPrefix,
      getBlockSummary,
      (lowerBound, upperBound) => query(lowerBound, upperBound)
    )
  }

  private def getByPrefix[A](
      blockHashPrefix: String,
      onFullHash: BlockHash => F[Option[A]],
      // lower bound, upper bound
      otherwise: (Array[Byte], Array[Byte]) => F[Option[A]]
  ): F[Option[A]] =
    64 - blockHashPrefix.length match {
      case 0 =>
        val decoded = ByteString.copyFrom(Base16.decode(blockHashPrefix))
        if (decoded.size() == 32) {
          onFullHash(decoded)
        } else {
          none[A].pure[F]
        }
      case x if x > 0 =>
        val lowerBound = Base16.decode(blockHashPrefix + "0" * x)
        val upperBound = Base16.decode(blockHashPrefix + "f" * x)

        (lowerBound.length, upperBound.length) match {
          case (32, 32) => otherwise(lowerBound, upperBound)
          case _        => none[A].pure[F]
        }
      case _ => none[A].pure[F]
    }

  override def isEmpty: F[Boolean] =
    sql"SELECT COUNT(*) FROM blocks".query[Long].unique.map(_ == 0L).transact(xa)

  override def put(blockHash: BlockHash, blockMsg: BlockMsgWithTransform): F[Unit] =
    Update[(BlockHash, TransformEntry)]("""|INSERT OR IGNORE INTO transforms
                                           |(block_hash, data)
                                           |VALUES (?, ?)""".stripMargin)
      .updateMany(blockMsg.transformEntry.map(t => (blockHash, t)).toList)
      .transact(xa)
      .void

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    sql"""|SELECT data
          |FROM block_metadata
          |WHERE block_hash=$blockHash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(xa)

  override def findBlockHashesWithDeployHash(deployHash: ByteString): F[Seq[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM deploy_process_results
          |WHERE deploy_hash=$deployHash
          |ORDER BY create_time_millis""".stripMargin.query[BlockHash].to[Seq].transact(xa)

  override def checkpoint(): F[Unit] = ().pure[F]

  override def clear(): F[Unit] =
    sql"DELETE FROM transforms".update.run.void.transact(xa)

  override def close(): F[Unit] = ().pure[F]
}

object SQLiteBlockStorage {
  private[storage] def create[F[_]](
      implicit xa: Transactor[F],
      metricsF: Metrics[F],
      syncF: Sync[F],
      fs2Compiler: Fs2Compiler[F]
  ): F[BlockStorage[F]] =
    for {
      blockStorage <- Sync[F].delay(new SQLiteBlockStorage[F](xa) with MeteredBlockStorage[F] {
                       override implicit val m: Metrics[F] = metricsF
                       override implicit val ms: Source =
                         Metrics.Source(BlockStorageMetricsSource, "sqlite")
                       override implicit val a: Apply[F] = syncF
                     })
    } yield blockStorage: BlockStorage[F]
}
