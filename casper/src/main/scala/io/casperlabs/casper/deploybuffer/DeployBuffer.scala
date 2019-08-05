package io.casperlabs.casper.deploybuffer

import cats._
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import io.casperlabs.casper.CasperMetricsSource
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.Time
import org.sqlite.{SQLiteErrorCode, SQLiteException}
import simulacrum.typeclass

import scala.concurrent.duration.FiniteDuration

//TODO: Doobie docs suggests exposing API as ConnectionIO to allow API users choose transaction model
@typeclass trait DeployBuffer[F[_]] {
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
  def markAsDiscardedByHashes(hashes: List[ByteString]): F[Unit]

  /** Will have an effect only on pending deploys.
    * After being discarded, deploys will be not be affected by any other 'mark*' methods. */
  def markAsDiscarded(deploys: List[Deploy]): F[Unit] =
    markAsDiscardedByHashes(deploys.map(_.deployHash))

  /** Will have an effect only on pending deploys.
    * Marks deploys as discarded that were added as pending more than 'now - expirationPeriod' time ago. */
  def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit]

  /** Deletes discarded deploys that are older than 'now - expirationPeriod'.
    * @return Number of deleted deploys */
  def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int]

  def readProcessed: F[List[Deploy]]

  def readProcessedByAccount(account: ByteString): F[List[Deploy]]

  def readProcessedHashes: F[List[ByteString]]

  def readPending: F[List[Deploy]]

  def readPendingHashes: F[List[ByteString]]

  def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]]

  def sizePendingOrProcessed(): F[Long]
}

class DeployBufferImpl[F[_]: Metrics: Time: Bracket[?[_], Throwable]](
    implicit val xa: Transactor[F]
) extends DeployBuffer[F] {
  // Do not forget updating Flyway migration scripts at:
  // block-storage/src/main/resources/db/migrations

  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  private val DiscardedStatusCode = 2

  import DeployBufferImpl.metricsSource

  private implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)
  private implicit val readDeploy: Read[Deploy] =
    Read[Array[Byte]].map(Deploy.parseFrom)

  override def addAsPending(deploys: List[Deploy]): F[Unit] =
    insertNewDeploys(deploys, PendingStatusCode)

  override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
    insertNewDeploys(deploys, ProcessedStatusCode)

  private def insertNewDeploys(
      deploys: List[Deploy],
      status: Int
  ): F[Unit] = {
    val writeToDeploysTable = Update[(ByteString, ByteString)](
      "INSERT INTO deploys (hash, data) VALUES (?, ?)"
    ).updateMany(deploys.map { d =>
      (d.deployHash, d.toByteString)
    })

    def writeToBufferedDeploysTable(currentTimeEpochSeconds: Long) =
      Update[(ByteString, Int, ByteString, Long, Long)](
        "INSERT INTO buffered_deploys (hash, status, account, update_time_seconds, receive_time_seconds) VALUES (?, ?, ?, ?, ?)"
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
      _ <- (writeToDeploysTable >> writeToBufferedDeploysTable(t))
            .handleError {
              case ex: SQLiteException
                  if ex.getResultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY =>
            }
            .transact(xa)
      _ <- updateMetrics()
    } yield ()
  }

  override def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes, ProcessedStatusCode, PendingStatusCode)

  override def markAsPendingByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes, PendingStatusCode, ProcessedStatusCode)

  override def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit] =
    Update[(ByteString, Int)](
      s"DELETE FROM buffered_deploys WHERE hash=? AND status=?"
    ).updateMany(hashes.map(h => (h, ProcessedStatusCode)))
      .transact(xa)
      .void

  override def markAsDiscardedByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes, DiscardedStatusCode, PendingStatusCode)

  override def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit] =
    for {
      now       <- Time[F].currentMillis
      threshold = now - expirationPeriod.toMillis
      _ <- sql"""|UPDATE buffered_deploys 
                 |SET status=$DiscardedStatusCode, update_time_seconds=$now
                 |WHERE status=$PendingStatusCode AND receive_time_seconds<$threshold""".stripMargin.update.run
            .transact(xa)
    } yield ()

  override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] = {
    def transaction(threshold: Long) =
      for {
        hashes <- sql"SELECT hash FROM buffered_deploys WHERE status=$DiscardedStatusCode AND update_time_seconds<$threshold"
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

  private def setStatus(hashes: List[ByteString], newStatus: Int, prevStatus: Int): F[Unit] =
    for {
      t <- Time[F].currentMillis
      _ <- Update[(Int, Long, ByteString, Int)](
            s"UPDATE buffered_deploys SET status=?, update_time_seconds=? WHERE hash=? AND status=?"
          ).updateMany(hashes.map((newStatus, t, _, prevStatus)))
            .transact(xa)
      _ <- updateMetrics()
    } yield ()

  override def readProcessed: F[List[Deploy]] =
    readByStatus(ProcessedStatusCode)

  override def readProcessedByAccount(account: ByteString): F[List[Deploy]] =
    readByAccountAndStatus(account, ProcessedStatusCode)

  override def readProcessedHashes: F[List[ByteString]] =
    readHashesByStatus(ProcessedStatusCode)

  override def readPending: F[List[Deploy]] =
    readByStatus(PendingStatusCode)

  override def readPendingHashes: F[List[ByteString]] =
    readHashesByStatus(PendingStatusCode)

  private def readByStatus(status: Int): F[List[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.status=$status""".stripMargin
      .query[Deploy]
      .to[List]
      .transact(xa)

  private def readByAccountAndStatus(account: ByteString, status: Int): F[List[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.account=$account AND bd.status=$status""".stripMargin
      .query[Deploy]
      .to[List]
      .transact(xa)

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

  override def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.hash=$hash AND (bd.status=$PendingStatusCode OR bd.status=$ProcessedStatusCode)""".stripMargin
      .query[Deploy]
      .option
      .transact(xa)
}

object DeployBufferImpl {
  private implicit val metricsSource: Source = Metrics.Source(CasperMetricsSource, "DeployBuffers")

  def create[F[_]: Metrics: Time: Sync](implicit xa: Transactor[F]): F[DeployBufferImpl[F]] =
    for {
      _            <- establishMetrics[F]
      deployBuffer <- Sync[F].delay(new DeployBufferImpl[F])
    } yield deployBuffer

  /** Export base 0 values so we have non-empty series for charts. */
  private def establishMetrics[F[_]: Monad: Metrics]: F[Unit] =
    for {
      _ <- Metrics[F].setGauge("pending_deploys", 0L)
      _ <- Metrics[F].setGauge("processed_deploys", 0L)
    } yield ()
}
