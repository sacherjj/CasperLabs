package io.casperlabs.casper.deploybuffer

import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.Deploy
import simulacrum.typeclass

@typeclass trait DeployStorageReader[F[_]] {
  def readProcessed: F[List[Deploy]]

  def readProcessedByAccount(account: ByteString): F[List[Deploy]]

  def readProcessedHashes: F[List[ByteString]]

  def readPending: F[List[Deploy]]

  def readPendingHashes: F[List[ByteString]]

  def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]]

  def sizePendingOrProcessed(): F[Long]

  def getByHashes(l: List[ByteString]): F[List[Deploy]]
}

class SQLiteDeployStorageReader[F[_]: Bracket[?[_], Throwable]](
    implicit val xa: Transactor[F]
) extends DeployStorageReader[F] {

  // Do not forget updating Flyway migration scripts at:
  // block-storage/src/main/resources/db/migrations

  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  // private val DiscardedStatusCode = 2

  private implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)
  // Doesn't work as implicit
  // Compiler: Cannot find or construct a Read instance for type ...
  private val readDeploy: Read[Deploy] =
    Read[Array[Byte]].map(Deploy.parseFrom)

  override def readProcessed: F[List[Deploy]] =
    readByStatus(ProcessedStatusCode)

  private def readByStatus(status: Int): F[List[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.status=$status""".stripMargin
      .query[Deploy](readDeploy)
      .to[List]
      .transact(xa)

  override def readProcessedByAccount(account: ByteString): F[List[Deploy]] =
    readByAccountAndStatus(account, ProcessedStatusCode)

  private def readByAccountAndStatus(account: ByteString, status: Int): F[List[Deploy]] =
    sql"""|SELECT data FROM deploys
          |INNER JOIN buffered_deploys bd on deploys.hash = bd.hash
          |WHERE bd.account=$account AND bd.status=$status""".stripMargin
      .query[Deploy](readDeploy)
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
      .query[Deploy](readDeploy)
      .option
      .transact(xa)

  override def getByHashes(l: List[ByteString]): F[List[Deploy]] =
    NonEmptyList
      .fromList[ByteString](l)
      .fold(List.empty[Deploy].pure[F])(nel => {
        val q = fr"SELECT data FROM deploys WHERE " ++ Fragments.in(fr"hash", nel) // "hash IN (…)"
        q.query[Deploy].to[List].transact(xa)
      })
}

object SQLiteDeployStorageReader {
  /* Implementation isn't mutable, so it's safe creating it without wrapping into an effect */
  def create[F[_]: Bracket[?[_], Throwable]](
      implicit xa: Transactor[F]
  ): DeployStorageReader[F] = new SQLiteDeployStorageReader[F]
}
