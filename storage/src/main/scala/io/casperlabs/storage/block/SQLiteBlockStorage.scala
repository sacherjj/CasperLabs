package io.casperlabs.storage.block

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.storage.block.BlockStorage.{BlockHash, DeployHash, MeteredBlockStorage}
import io.casperlabs.storage.util.DoobieCodecs
import io.casperlabs.storage.{BlockMsgWithTransform, BlockStorageMetricsSource}

class SQLiteBlockStorage[F[_]: Bracket[*[_], Throwable]: Fs2Compiler](
    readXa: Transactor[F],
    writeXa: Transactor[F]
) extends BlockStorage[F]
    with DoobieCodecs {

  import SQLiteBlockStorage.blockInfoCols

  override def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    get(sql"""|SELECT block_hash, data
              |FROM block_metadata
              |WHERE block_hash=$blockHash""".stripMargin.query[(BlockHash, BlockSummary)].option)

  private def get(
      initial: ConnectionIO[Option[(BlockHash, BlockSummary)]]
  ): F[Option[BlockMsgWithTransform]] = {
    def createTransaction(blockHash: BlockHash, blockSummary: BlockSummary) =
      for {
        body <- sql"""|SELECT d.summary, d.body, dpr.deploy_position, dpr.cost, dpr.execution_error_message
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
        transforms <- sql"""|SELECT stage, data
                            |FROM transforms
                            |WHERE block_hash=$blockHash""".stripMargin
                       .query[(Int, TransformEntry)]
                       .to[List]
                       .map(_.groupBy(_._1).mapValues(_.map(_._2)))
                       .map(BlockStorage.blockEffectsMapToProto(_))
      } yield BlockMsgWithTransform(
        Block(blockSummary.blockHash, blockSummary.header, body.some, blockSummary.signature).some,
        transforms
      ).some

    val transaction = initial.flatMap(_.fold(none[BlockMsgWithTransform].pure[ConnectionIO]) {
      case (blockHash, blockSummary) => createTransaction(blockHash, blockSummary)
    })
    transaction.transact(readXa)
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

  override def getBlockInfoByPrefix(blockHashPrefix: String): F[Option[BlockInfo]] = {
    def query(lowerBound: Array[Byte], upperBound: Array[Byte]) =
      (fr"""SELECT """ ++ blockInfoCols() ++ fr"""
            FROM block_metadata
            WHERE block_hash>=$lowerBound AND block_hash<=$upperBound
            LIMIT 1""")
        .query[BlockInfo]
        .option
        .transact(readXa)

    getByPrefix[BlockInfo](
      blockHashPrefix,
      getBlockInfo,
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
    sql"SELECT COUNT(*) FROM blocks".query[Long].unique.map(_ == 0L).transact(readXa)

  override def put(blockHash: BlockHash, blockMsg: BlockMsgWithTransform): F[Unit] =
    Update[(BlockHash, Int, TransformEntry)]("""|INSERT OR IGNORE INTO transforms
                                           |(block_hash, stage, data)
                                           |VALUES (?, ?, ?)""".stripMargin)
      .updateMany(
        blockMsg.blockEffects
          .flatMap(
            effectsGroup => effectsGroup.effects.toList.map(t => (blockHash, effectsGroup.stage, t))
          )
          .toList
      )
      .transact(writeXa)
      .void

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    getBlockInfo(blockHash).map(_.flatMap(_.summary))

  override def getBlockInfo(blockHash: BlockHash): F[Option[BlockInfo]] =
    (fr"""SELECT """ ++ blockInfoCols() ++ fr"""
          FROM block_metadata
          WHERE block_hash=$blockHash""")
      .query[BlockInfo]
      .option
      .transact(readXa)

  override def findBlockHashesWithDeployHashes(
      deployHashes: List[DeployHash]
  ): F[Map[DeployHash, Set[BlockHash]]] =
    NonEmptyList
      .fromList[ByteString](deployHashes)
      .fold(Map.empty[DeployHash, Set[BlockHash]].pure[F]) { nfl =>
        val sql = fr"""|SELECT deploy_hash, block_hash
                       |FROM deploy_process_results
                       |WHERE """.stripMargin ++ Fragments.in(fr"deploy_hash", nfl)

        sql
          .query[(DeployHash, BlockHash)]
          .to[Seq]
          .transact(readXa)
          .map(_.groupBy(_._1))
          .map { deployHashToBlockHashesMap: Map[DeployHash, Seq[(DeployHash, BlockHash)]] =>
            deployHashes.map { d =>
              val value =
                deployHashToBlockHashesMap.get(d).fold(Set.empty[BlockHash])(_.map(_._2).toSet)
              (d, value)
            }.toMap
          }
      }

  override def checkpoint(): F[Unit] = ().pure[F]

  override def clear(): F[Unit] =
    sql"DELETE FROM transforms".update.run.void.transact(writeXa)

  override def close(): F[Unit] = ().pure[F]
}

object SQLiteBlockStorage {
  private[storage] def create[F[_]](readXa: Transactor[F], writeXa: Transactor[F])(
      implicit
      metricsF: Metrics[F],
      syncF: Sync[F],
      fs2Compiler: Fs2Compiler[F]
  ): F[BlockStorage[F]] =
    for {
      blockStorage <- Sync[F].delay(
                       new SQLiteBlockStorage[F](readXa, writeXa) with MeteredBlockStorage[F] {
                         override implicit val m: Metrics[F] = metricsF
                         override implicit val ms: Source =
                           Metrics.Source(BlockStorageMetricsSource, "sqlite")
                         override implicit val a: Apply[F] = syncF
                       }
                     )
    } yield blockStorage: BlockStorage[F]

  // Helper function to avoid having to duplicate the list of columns of `BlockInfo` to read it from the `block_metadata` table.
  private[storage] def blockInfoCols(alias: String = "") = {
    val cols =
      Seq("data", "block_size", "deploy_error_count", "deploy_cost_total", "deploy_gas_price_avg")
    Fragment.const(cols.map(col => if (alias.isEmpty) col else s"${alias}.${col}").mkString(", "))
  }
}
