package io.casperlabs.storage.deploy

import cats._
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.Time
import io.casperlabs.storage.DeployStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.DeployHash
import io.casperlabs.storage.util.DoobieCodecs

import scala.concurrent.duration._

class SQLiteDeployStorage[F[_]: Metrics: Time: Sync](chunkSize: Int)(
    implicit val xa: Transactor[F],
    metricsSource: Source
) extends DeployStorage[F]
    with DoobieCodecs {
  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  private val DiscardedStatusCode = 2

  private val StatusMessageTtlExpired = "TTL expired"

  override def addAsExecuted(block: Block): F[Unit] = {
    val writeToDeploysTable = Update[(ByteString, ByteString, Long, ByteString)](
      "INSERT OR IGNORE INTO deploys (hash, account, create_time_millis, data) VALUES (?, ?, ?, ?)"
    ).updateMany(
      block.getBody.deploys.toList.map(
        pd =>
          (
            pd.getDeploy.deployHash,
            pd.getDeploy.getHeader.accountPublicKey,
            pd.getDeploy.getHeader.timestamp,
            pd.getDeploy.toByteString
          )
      )
    )

    val writeToProcessResultsTable =
      Update[(ByteString, ByteString, Int, ByteString, Long, Long, Long, Option[String])](
        """
          |INSERT OR IGNORE INTO deploy_process_results
          |(
          | block_hash,
          | deploy_hash,
          | deploy_position,
          | account,
          | create_time_millis,
          | execute_time_millis,
          | cost,
          | execution_error_message
          |) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          |""".stripMargin
      ).updateMany(
        block.getBody.deploys.zipWithIndex.map {
          case (pd, deployPosition) =>
            (
              block.blockHash,
              pd.getDeploy.deployHash,
              deployPosition,
              pd.getDeploy.getHeader.accountPublicKey,
              pd.getDeploy.getHeader.timestamp,
              block.getHeader.timestamp,
              pd.cost,
              if (pd.isError) pd.errorMessage.some else none[String]
            )
        }.toList
      )

    (writeToDeploysTable >> writeToProcessResultsTable).transact(xa).void
  }

  override def addAsPending(deploys: List[Deploy]): F[Unit] =
    insertNewDeploys(deploys, PendingStatusCode)

  override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
    insertNewDeploys(deploys, ProcessedStatusCode)

  private def insertNewDeploys(
      deploys: List[Deploy],
      status: Int
  ): F[Unit] = {
    val writeToDeploysTable = Update[(ByteString, ByteString, Long, ByteString)](
      "INSERT OR IGNORE INTO deploys (hash, account, create_time_millis, data) VALUES (?, ?, ?, ?)"
    ).updateMany(deploys.map { d =>
      (d.deployHash, d.getHeader.accountPublicKey, d.getHeader.timestamp, d.toByteString)
    })

    def writeToBufferedDeploysTable(currentTimeEpochMillis: Long) =
      Update[(ByteString, Int, ByteString, Long, Long)](
        "INSERT OR IGNORE INTO buffered_deploys (hash, status, account, update_time_millis, receive_time_millis) VALUES (?, ?, ?, ?, ?)"
      ).updateMany(deploys.map { d =>
          (
            d.deployHash,
            status,
            d.getHeader.accountPublicKey,
            currentTimeEpochMillis,
            currentTimeEpochMillis
          )
        })
        .void

    val writeToDeployHeadersTable = Update[(ByteString, ByteString, Long, ByteString)](
      "INSERT OR IGNORE INTO deploy_headers (hash, account, timestamp_millis, header) VALUES (?, ?, ?, ?)"
    ).updateMany(deploys.map { d =>
      (d.deployHash, d.getHeader.accountPublicKey, d.getHeader.timestamp, d.getHeader.toByteString)
    })

    for {
      t <- Time[F].currentMillis
      _ <- (writeToDeploysTable >> writeToBufferedDeploysTable(t) >> writeToDeployHeadersTable)
            .transact(xa)
      _ <- updateMetrics()
    } yield ()
  }

  override def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes.map((_, none[String])), ProcessedStatusCode, PendingStatusCode)

  override def markAsPendingByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes.map((_, none[String])), PendingStatusCode, ProcessedStatusCode)

  override def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit] =
    Update[(ByteString, Int)](
      s"DELETE FROM buffered_deploys WHERE hash=? AND status=?"
    ).updateMany(hashes.map(h => (h, ProcessedStatusCode)))
      .transact(xa)
      .void

  private def setStatus(
      hashesAndStatusMessages: List[(ByteString, Option[String])],
      newStatus: Int,
      prevStatus: Int
  ): F[Unit] =
    for {
      t <- Time[F].currentMillis
      _ <- Update[(Int, Long, Option[String], ByteString, Int)](
            s"UPDATE buffered_deploys SET status=?, update_time_millis=?, status_message=? WHERE hash=? AND status=?"
          ).updateMany(hashesAndStatusMessages.map {
              case (hash, maybeStatusMessage) =>
                (newStatus, t, maybeStatusMessage, hash, prevStatus)
            })
            .transact(xa)
      _ <- updateMetrics()
    } yield ()

  override def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]): F[Unit] =
    setStatus(hashesAndReasons.map {
      case (h, r) => (h, r.some)
    }, DiscardedStatusCode, PendingStatusCode)

  override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] = {
    def transaction(threshold: Long) =
      for {
        hashes <- sql"SELECT hash FROM buffered_deploys WHERE status=$DiscardedStatusCode AND update_time_millis<$threshold"
                   .query[ByteString]
                   .to[List]
        _ <- Update[ByteString](s"DELETE FROM buffered_deploys WHERE hash=?").updateMany(hashes)
        deletedNum <- Update[ByteString](
                       s"""|DELETE
                           |FROM deploys
                           |WHERE hash = ?
                           |  AND NOT EXISTS(SELECT 1
                           |                 FROM deploy_process_results dpr
                           |                 WHERE deploys.hash = dpr.deploy_hash)""".stripMargin
                     ).updateMany(hashes)
      } yield deletedNum

    for {
      now        <- Time[F].currentMillis
      threshold  = now - expirationPeriod.toMillis
      deletedNum <- transaction(threshold).transact(xa)
    } yield deletedNum
  }

  override def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit] =
    for {
      now       <- Time[F].currentMillis
      threshold = now - expirationPeriod.toMillis
      _ <- sql"""|UPDATE buffered_deploys 
                 |SET status=$DiscardedStatusCode, update_time_millis=$now, status_message=$StatusMessageTtlExpired
                 |WHERE status=$PendingStatusCode AND receive_time_millis<$threshold""".stripMargin.update.run
            .transact(xa)
    } yield ()

  private def countByStatus(status: Int): F[Long] =
    sql"SELECT COUNT(hash) FROM buffered_deploys WHERE status=$status"
      .query[Long]
      .unique
      .transact(xa)

  private def updateMetrics(): F[Unit] =
    for {
      pending   <- countByStatus(PendingStatusCode)
      processed <- countByStatus(ProcessedStatusCode)
      _         <- Metrics[F].setGauge("pending_deploys", pending)
      _         <- Metrics[F].setGauge("processed_deploys", processed)
    } yield ()

  override def readProcessed: F[List[Deploy]] =
    readByStatus(ProcessedStatusCode)

  override def readPendingHeaders: F[List[Deploy.Header]] =
    readHeadersByStatus(PendingStatusCode)

  override def readPendingHashesAndHeaders: fs2.Stream[F, (ByteString, Deploy.Header)] =
    readHashesAndHeadersByStatus(PendingStatusCode)

  private def readHeadersByStatus(status: Int): F[List[Deploy.Header]] =
    sql"""|SELECT header FROM deploy_headers
          |INNER JOIN buffered_deploys bd on deploy_headers.hash = bd.hash
          |WHERE bd.status=$status""".stripMargin
      .query[Deploy.Header]
      .to[List]
      .transact(xa)

  private def readHashesAndHeadersByStatus(
      status: Int
  ): fs2.Stream[F, (ByteString, Deploy.Header)] =
    sql"""|SELECT dh.hash, dh.header FROM deploy_headers dh
          |INNER JOIN buffered_deploys bd on dh.hash = bd.hash
          |WHERE bd.status=$status""".stripMargin
      .query[(ByteString, Deploy.Header)]
      .streamWithChunkSize(chunkSize)
      .transact(xa)

  private def readByStatus(status: Int): F[List[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.status=$status""".stripMargin
      .query[Deploy]
      .to[List]
      .transact(xa)

  override def readProcessedByAccount(account: ByteString): F[List[Deploy]] =
    readByAccountAndStatus(account, ProcessedStatusCode)

  private def readByAccountAndStatus(account: ByteString, status: Int): F[List[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.account=$account AND bd.status=$status""".stripMargin
      .query[Deploy]
      .to[List]
      .transact(xa)

  override def readProcessedHashes: F[List[ByteString]] =
    readHashesByStatus(ProcessedStatusCode)

  override def readPending: F[List[Deploy]] =
    readByStatus(PendingStatusCode)

  override def readPendingHashes: F[List[ByteString]] =
    readHashesByStatus(PendingStatusCode)

  private def readHashesByStatus(status: Int): F[List[ByteString]] =
    sql"SELECT hash FROM buffered_deploys WHERE status=$status"
      .query[ByteString]
      .to[List]
      .transact(xa)

  override def sizePendingOrProcessed(): F[Long] =
    sql"SELECT COUNT(hash) FROM buffered_deploys WHERE status=$PendingStatusCode OR status=$ProcessedStatusCode"
      .query[Long]
      .unique
      .transact(xa)

  override def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.hash=$hash AND (bd.status=$PendingStatusCode OR bd.status=$ProcessedStatusCode)""".stripMargin
      .query[Deploy]
      .option
      .transact(xa)

  override def getByHashes(l: Set[ByteString]): fs2.Stream[F, Deploy] =
    NonEmptyList
      .fromList[ByteString](l.toList)
      .fold(fs2.Stream.fromIterator[F](List.empty[Deploy].toIterator))(nel => {
        val q = fr"SELECT data FROM deploys WHERE " ++ Fragments.in(fr"hash", nel) // "hash IN (…)"
        q.query[Deploy].streamWithChunkSize(chunkSize).transact(xa)
      })

  override def getProcessingResults(
      hash: ByteString
  ): F[List[(ByteString, ProcessedDeploy)]] = {
    val getDeploy =
      sql"SELECT data FROM deploys WHERE hash=$hash".query[Deploy].unique.transact(xa)

    val readProcessingResults =
      sql"""|SELECT block_hash, cost, execution_error_message 
            |FROM deploy_process_results 
            |WHERE deploy_hash=$hash 
            |ORDER BY execute_time_millis DESC""".stripMargin
        .query[(ByteString, ProcessedDeploy)]
        .to[List]
        .transact(xa)

    for {
      blockHashesAndProcessingResults <- readProcessingResults
      res <- if (blockHashesAndProcessingResults.isEmpty) blockHashesAndProcessingResults.pure[F]
            else
              getDeploy.map(
                d =>
                  blockHashesAndProcessingResults.map {
                    case (blockHash, processingResult) =>
                      (blockHash, processingResult.withDeploy(d))
                  }
              )
    } yield res
  }

  override def getDeployInfo(deployHash: DeployHash): F[Option[DeployInfo]] = {
    val getDeploy =
      sql"SELECT data FROM deploys WHERE hash=$deployHash".query[Deploy].option.transact(xa)

    val processingResults =
      sql"""|SELECT dpr.cost, dpr.execution_error_message, bm.data, bm.block_size, bm.deploy_error_count
            |FROM deploy_process_results dpr 
            |LEFT OUTER JOIN block_metadata bm ON dpr.block_hash = bm.block_hash
            |WHERE dpr.deploy_hash = $deployHash""".stripMargin
        .query[ProcessingResult]
        .to[List]
        .transact(xa)

    for {
      deploy <- getDeploy
      deployInfo <- deploy match {
                     case None =>
                       none[DeployInfo].pure[F]
                     case Some(d) =>
                       processingResults.map(p => DeployInfo(d.some, p).some)
                   }
    } yield deployInfo
  }

  override def clear(): F[Unit] =
    (for {
      _ <- sql"DELETE FROM deploys".update.run
      _ <- sql"DELETE FROM buffered_deploys".update.run
      _ <- sql"DELETE FROM deploy_process_results".update.run
    } yield ()).transact(xa)

  override def close(): F[Unit] = ().pure[F]
}

object SQLiteDeployStorage {
  private implicit val metricsSource: Source = Metrics.Source(DeployStorageMetricsSource, "sqlite")

  private[storage] def create[F[_]: Metrics: Time: Sync](
      deployStorageChunkSize: Int
  )(implicit xa: Transactor[F]): F[DeployStorage[F]] =
    for {
      _ <- establishMetrics[F]
    } yield new SQLiteDeployStorage[F](deployStorageChunkSize): DeployStorage[F]

  /** Export base 0 values so we have non-empty series for charts. */
  private def establishMetrics[F[_]: Monad: Metrics]: F[Unit] =
    for {
      _ <- Metrics[F].setGauge("pending_deploys", 0L)
      _ <- Metrics[F].setGauge("processed_deploys", 0L)
    } yield ()
}
