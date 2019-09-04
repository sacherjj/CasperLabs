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
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.storage.block.BlockStorage.{BlockHash, BlockHashPrefix, MeteredBlockStorage}
import io.casperlabs.storage.util.DoobieCodecs
import io.casperlabs.storage.{BlockMsgWithTransform, BlockStorageMetricsSource}

class SQLiteBlockStorage[F[_]: Bracket[?[_], Throwable]: Fs2Compiler](
    xa: Transactor[F],
    //TODO: It's used only in the legacy transport layer, so it should be fine storing it in memory
    //We'll need to remove it when we drop legacy transport layer
    approvedBlockRef: Ref[F, Option[ApprovedBlock]]
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

  override def getByPrefix(blockHashPrefix: BlockHashPrefix): F[Option[BlockMsgWithTransform]] = {
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

  override def getSummaryByPrefix(blockHashPrefix: BlockHashPrefix): F[Option[BlockSummary]] = {
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
      blockHashPrefix: BlockHashPrefix,
      onFullHash: BlockHash => F[Option[A]],
      // lower bound, upper bound
      otherwise: (Array[Byte], Array[Byte]) => F[Option[A]]
  ): F[Option[A]] = {
    val length = blockHashPrefix.size()
    32 - length match {
      case 0 => onFullHash(blockHashPrefix)
      case x if x > 0 =>
        val asArray    = blockHashPrefix.toByteArray
        val lowerBound = asArray ++ Array.fill(x)(0.toByte)
        val upperBound = asArray ++ Base16.decode("ff" * x)
        otherwise(lowerBound, upperBound)
      case _ => none[A].pure[F]
    }
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

  override def getApprovedBlock(): F[Option[ApprovedBlock]] = approvedBlockRef.get

  override def putApprovedBlock(block: ApprovedBlock): F[Unit] = approvedBlockRef.set(block.some)

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    sql"""|SELECT data
          |FROM block_metadata
          |WHERE block_hash=$blockHash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(xa)

  override def findBlockHashesWithDeployhash(deployHash: ByteString): F[Seq[BlockHash]] =
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
      ref <- Ref.of[F, Option[ApprovedBlock]](None)
      blockStorage <- Sync[F].delay(new SQLiteBlockStorage[F](xa, ref) with MeteredBlockStorage[F] {
                       override implicit val m: Metrics[F] = metricsF
                       override implicit val ms: Source =
                         Metrics.Source(BlockStorageMetricsSource, "sqlite")
                       override implicit val a: Apply[F] = syncF
                     })
    } yield blockStorage: BlockStorage[F]
}
