package io.casperlabs.casper.deploybuffer

import cats._
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import io.casperlabs.blockstorage.DeployStorageMetricsSource
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.Time
import simulacrum.typeclass

import scala.concurrent.duration._

@typeclass trait DeployStorageWriter[F[_]] {
  /* Should not fail if the same deploy added twice */
  def addAsPending(deploys: List[Deploy]): F[Unit]

  /* Should not fail if the same deploy added twice */
  def addAsProcessed(deploys: List[Deploy]): F[Unit]

  /* Will have an effect only on pending deploys */
  def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit]

  /* Will have an effect only on pending deploys */
  def markAsProcessed(deploys: List[Deploy]): F[Unit] =
    markAsProcessedByHashes(deploys.map(_.deployHash))

  /* Will have an effect only on processed deploys */
  def markAsPendingByHashes(hashes: List[ByteString]): F[Unit]

  /* Will have an effect only on processed deploys */
  def markAsPending(deploys: List[Deploy]): F[Unit] =
    markAsPendingByHashes(deploys.map(_.deployHash))

  /** Will have an effect only on processed deploys.
    * After being finalized, deploys will be not be affected by any other 'mark*' methods. */
  def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit]

  /** Will have an effect only on processed deploys.
    * After being finalized, deploys will be not be affected by any other 'mark*' methods. */
  def markAsFinalized(deploys: List[Deploy]): F[Unit] =
    markAsFinalizedByHashes(deploys.map(_.deployHash))

  /** Will have an effect only on pending deploys.
    * After being discarded, deploys will be not be affected by any other 'mark*' methods. */
  def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]): F[Unit]

  /** Will have an effect only on pending deploys.
    * After being discarded, deploys will be not be affected by any other 'mark*' methods. */
  def markAsDiscarded(deploysAndReasons: List[(Deploy, String)]): F[Unit] =
    markAsDiscardedByHashes(deploysAndReasons.map {
      case (d, message) => (d.deployHash, message)
    })

  /** Will have an effect only on pending deploys.
    * Marks deploys as discarded that were added as pending more than 'now - expirationPeriod' time ago. */
  def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit]

  /** Deletes discarded deploys that are older than 'now - expirationPeriod'.
    * @return Number of deleted deploys */
  def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int]
}

class SQLiteDeployStorageWriter[F[_]: Metrics: Time: Bracket[?[_], Throwable]](
    implicit val xa: Transactor[F]
) extends DeployStorageWriter[F] {
  // Do not forget updating Flyway migration scripts at:
  // block-storage/src/main/resources/db/migrations

  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  private val DiscardedStatusCode = 2

  private val StatusMessageTtlExpired = "TTL expired"

  import SQLiteDeployStorageWriter.metricsSource

  private implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)

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

    def writeToBufferedDeploysTable(currentTimeEpochSeconds: Long) =
      Update[(ByteString, Int, ByteString, Long, Long)](
        "INSERT OR IGNORE INTO buffered_deploys (hash, status, account, update_time_millis, receive_time_millis) VALUES (?, ?, ?, ?, ?)"
      ).updateMany(deploys.map { d =>
          (
            d.deployHash,
            status,
            d.getHeader.accountPublicKey,
            currentTimeEpochSeconds,
            currentTimeEpochSeconds
          )
        })
        .void

    for {
      t <- Time[F].currentMillis
      _ <- (writeToDeploysTable >> writeToBufferedDeploysTable(t)).transact(xa)
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
        _ <- Update[ByteString](s"DELETE FROM deploys WHERE hash=?").updateMany(hashes)
      } yield hashes.size

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
}

object SQLiteDeployStorageWriter {
  private implicit val metricsSource: Source = Metrics.Source(DeployStorageMetricsSource, "sqlite")

  def create[F[_]: Metrics: Time: Bracket[?[_], Throwable]](
      implicit xa: Transactor[F]
  ): F[DeployStorageWriter[F]] =
    for {
      _ <- establishMetrics[F]
    } yield new SQLiteDeployStorageWriter[F]: DeployStorageWriter[F]

  /** Export base 0 values so we have non-empty series for charts. */
  private def establishMetrics[F[_]: Monad: Metrics]: F[Unit] =
    for {
      _ <- Metrics[F].setGauge("pending_deploys", 0L)
      _ <- Metrics[F].setGauge("processed_deploys", 0L)
    } yield ()
}
