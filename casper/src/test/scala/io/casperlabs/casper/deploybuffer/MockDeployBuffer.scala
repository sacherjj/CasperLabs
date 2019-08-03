package io.casperlabs.casper.deploybuffer

import cats.{Functor, Semigroupal}
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.deploybuffer.MockDeployBuffer.Metadata

import scala.concurrent.duration.FiniteDuration

class MockDeployBuffer[F[_]: Functor: Semigroupal](
    deploysWithMetadataRef: Ref[F, Map[Deploy, Metadata]]
) extends DeployBuffer[F] {

  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  private val DiscardedStatusCode = 2

  override def addAsPending(deploys: List[Deploy]): F[Unit] =
    addWithStatus(deploys, PendingStatusCode)

  override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
    addWithStatus(deploys, ProcessedStatusCode)

  private def addWithStatus(deploys: List[Deploy], status: Int): F[Unit] =
    deploysWithMetadataRef.update(
      _ ++ deploys
        .map(d => (d, Metadata(status, System.currentTimeMillis(), System.currentTimeMillis())))
        .toMap
    )

  override def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes, ProcessedStatusCode, PendingStatusCode)

  override def markAsPendingByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes, PendingStatusCode, ProcessedStatusCode)

  override def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit] =
    deploysWithMetadataRef.update(_.filter {
      case (deploy, Metadata(`ProcessedStatusCode`, _, _)) if hashes.toSet(deploy.deployHash) =>
        false
      case _ => true
    })

  private def setStatus(hashes: List[ByteString], newStatus: Int, prevStatus: Int): F[Unit] =
    deploysWithMetadataRef.update(_.map {
      case (deploy, Metadata(`prevStatus`, _, createdAt)) if hashes.toSet(deploy.deployHash) =>
        (deploy, Metadata(newStatus, System.currentTimeMillis(), createdAt))
      case x => x
    })

  override def markAsDiscardedByHashes(hashes: List[ByteString]): F[Unit] =
    setStatus(hashes, DiscardedStatusCode, PendingStatusCode)

  override def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit] =
    deploysWithMetadataRef.update(_.map {
      case (deploy, Metadata(`PendingStatusCode`, updatedAt, createdAt))
          if updatedAt < System.currentTimeMillis() - expirationPeriod.toMillis =>
        (deploy, Metadata(DiscardedStatusCode, System.currentTimeMillis(), createdAt))
      case x => x
    })

  override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] = {
    val f: ((Deploy, Metadata)) => Boolean = (_: (Deploy, Metadata)) match {
      case (_, Metadata(`DiscardedStatusCode`, updatedAt, _))
          if updatedAt < System.currentTimeMillis() - expirationPeriod.toMillis =>
        true
      case _ => false
    }

    deploysWithMetadataRef.modify { ds =>
      val deletedNum = ds.count(f)
      (ds.filter(f andThen (_.unary_!)), deletedNum)
    }
  }

  override def readProcessed: F[List[Deploy]] = readByStatus(ProcessedStatusCode)

  override def readProcessedByAccount(account: ByteString): F[List[Deploy]] =
    readProcessed.map(_.filter(_.getHeader.accountPublicKey == account))

  override def readProcessedHashes: F[List[ByteString]] = readProcessed.map(_.map(_.deployHash))

  override def readPending: F[List[Deploy]] = readByStatus(PendingStatusCode)

  override def readPendingHashes: F[List[ByteString]] = readPending.map(_.map(_.deployHash))

  private def readByStatus(status: Int): F[List[Deploy]] =
    deploysWithMetadataRef.get.map(_.collect {
      case (deploy, Metadata(`status`, _, _)) => deploy
    }.toList)

  override def sizePendingOrProcessed(): F[Long] =
    (readProcessed, readPending).mapN(_.size + _.size).map(_.toLong)

  override def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]] =
    deploysWithMetadataRef.get.map(_.collectFirst {
      case (d, Metadata(`PendingStatusCode` | `ProcessedStatusCode`, _, _))
          if d.deployHash == hash =>
        d
    })
}

object MockDeployBuffer {
  case class Metadata(status: Int, updatedAt: Long, createdAt: Long)

  def create[F[_]: Sync](): F[DeployBuffer[F]] =
    for {
      ref <- Ref.of[F, Map[Deploy, Metadata]](Map.empty)
    } yield new MockDeployBuffer[F](ref): DeployBuffer[F]

  def unsafeCreate[F[_]: Sync](): DeployBuffer[F] =
    new MockDeployBuffer[F](Ref.unsafe[F, Map[Deploy, Metadata]](Map.empty))
}
