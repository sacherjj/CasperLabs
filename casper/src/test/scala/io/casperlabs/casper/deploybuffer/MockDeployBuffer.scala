package io.casperlabs.casper.deploybuffer

import cats.Monad
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.deploybuffer.MockDeployBuffer.Metadata
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.shared.Log

import scala.concurrent.duration.FiniteDuration

class MockDeployBuffer[F[_]: Monad: Log](
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
    logOperation(
      s"addAsPending(deploys = ${deploysToString(deploys)})",
      addWithStatus(deploys, PendingStatusCode)
    )

  override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
    logOperation(
      s"addAsProcessed(deploys = ${deploysToString(deploys)})",
      addWithStatus(deploys, ProcessedStatusCode)
    )

  private def addWithStatus(deploys: List[Deploy], status: Int): F[Unit] =
    deploysWithMetadataRef.update(
      _ ++ deploys
        .map(d => (d, Metadata(status, System.currentTimeMillis(), System.currentTimeMillis())))
        .toMap
    )

  override def markAsPendingByHashes(hashes: List[ByteString]): F[Unit] =
    logOperation(
      s"markAsPending(hashes = ${hashesToString(hashes)})",
      setStatus(hashes, PendingStatusCode, ProcessedStatusCode)
    )

  override def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit] =
    logOperation(
      s"markAsProcessed(hashes = ${hashesToString(hashes)})",
      setStatus(hashes, ProcessedStatusCode, PendingStatusCode)
    )

  override def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit] =
    logOperation(
      s"markAsFinalized(hashes = ${hashesToString(hashes)})",
      deploysWithMetadataRef.update(_.filter {
        case (deploy, Metadata(`ProcessedStatusCode`, _, _)) if hashes.toSet(deploy.deployHash) =>
          false
        case _ => true
      })
    )

  override def markAsDiscardedByHashes(hashes: List[ByteString]): F[Unit] =
    logOperation(
      s"markAsDiscarded(hashes = ${hashesToString(hashes)})",
      setStatus(hashes, DiscardedStatusCode, PendingStatusCode)
    )

  private def setStatus(hashes: List[ByteString], newStatus: Int, prevStatus: Int): F[Unit] =
    deploysWithMetadataRef.update(_.map {
      case (deploy, Metadata(`prevStatus`, _, createdAt)) if hashes.toSet(deploy.deployHash) =>
        (deploy, Metadata(newStatus, System.currentTimeMillis(), createdAt))
      case x => x
    }) >> logCurrentState()

  override def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit] =
    logOperation(
      s"markAsDiscarded(expirationPeriod = ${expirationPeriod.toCoarsest.toString()})",
      deploysWithMetadataRef.update(_.map {
        case (deploy, Metadata(`PendingStatusCode`, updatedAt, createdAt))
            if updatedAt < System.currentTimeMillis() - expirationPeriod.toMillis =>
          (deploy, Metadata(DiscardedStatusCode, System.currentTimeMillis(), createdAt))
        case x => x
      })
    )

  override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] = {
    val f: ((Deploy, Metadata)) => Boolean = (_: (Deploy, Metadata)) match {
      case (_, Metadata(`DiscardedStatusCode`, updatedAt, _))
          if updatedAt < System.currentTimeMillis() - expirationPeriod.toMillis =>
        true
      case _ => false
    }

    logOperation("cleanupDiscarded", deploysWithMetadataRef.modify { ds =>
      val deletedNum = ds.count(f)
      (ds.filter(f andThen (_.unary_!)), deletedNum)
    })
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

  private def logOperation[A](opMsg: String, op: F[A]): F[A] =
    for {
      _ <- Log[F].info(s"DeployBuffer.$opMsg")
      a <- op
      _ <- logCurrentState()
    } yield a

  private def logCurrentState(): F[Unit] = {
    def statusToString(s: Int): String = s match {
      case 0 => "pending"
      case 1 => "processed"
      case 2 => "discarded"
    }

    val getStateDesc = deploysWithMetadataRef.get.map { deploys =>
      deploys.toList
        .groupBy(_._2.status)
        .map {
          case (status, ds) =>
            val s = statusToString(status)
            val dss = ds
              .map {
                case (d, _) => deployToString(d)
              }
              .mkString(", ")
            s + ": " + dss
        }
        .mkString("; ")
    }

    def log(stateDesc: String): F[Unit] =
      Log[F].info(s"Current state of deploy buffer: $stateDesc")

    for {
      stateDesc <- getStateDesc
      _         <- log(stateDesc)
    } yield ()
  }

  private def deployToString(d: Deploy): String    = Base16.encode(d.deployHash.toByteArray).take(4)
  private def hashToString(hs: ByteString): String = Base16.encode(hs.toByteArray).take(4)

  private def deploysToString(ds: List[Deploy]): String    = ds.map(deployToString).mkString(", ")
  private def hashesToString(hs: List[ByteString]): String = hs.map(hashToString).mkString(", ")
}

object MockDeployBuffer {
  case class Metadata(status: Int, updatedAt: Long, createdAt: Long)

  def create[F[_]: Sync: Log](): F[DeployBuffer[F]] =
    for {
      ref <- Ref.of[F, Map[Deploy, Metadata]](Map.empty)
    } yield new MockDeployBuffer[F](ref): DeployBuffer[F]

  def unsafeCreate[F[_]: Sync: Log](): DeployBuffer[F] =
    new MockDeployBuffer[F](Ref.unsafe[F, Map[Deploy, Metadata]](Map.empty))
}
