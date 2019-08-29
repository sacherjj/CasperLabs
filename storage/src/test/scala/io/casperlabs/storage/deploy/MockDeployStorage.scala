package io.casperlabs.storage.deploy

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.storage.deploy.MockDeployStorage.Metadata
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.shared.Log

import scala.concurrent.duration.FiniteDuration

class MockDeployStorage[F[_]: Sync: Log](
    deploysWithMetadataRef: Ref[F, Map[Deploy, Metadata]]
) extends DeployStorage[F] {

  // Deploys not yet included in a block
  private val PendingStatusCode = 0
  // Deploys that have been processed at least once,
  // waiting to be finalized or orphaned
  private val ProcessedStatusCode = 1
  // Deploys that have been discarded for some reason and should be deleted after a while
  private val DiscardedStatusCode        = 2
  private val ExecutionErrorStatusCode   = 3
  private val ExecutionSuccessStatusCode = 4

  override def addAsExecuted(block: Block): F[Unit] =
    logOperation(
      s"addAsExecuted(blockHash = ${blockHashToString(block)}, deploys = ${processedDeploysToString(block)})",
      deploysWithMetadataRef.update { deploys =>
        def metadata(pd: Block.ProcessedDeploy, acc: Map[Deploy, Metadata]) = {
          val prev = acc.getOrElse(
            pd.getDeploy,
            Metadata(
              if (pd.isError) ExecutionErrorStatusCode else ExecutionSuccessStatusCode,
              now,
              now,
              Nil
            )
          )
          prev.copy(processingResults = (block.blockHash, pd) :: prev.processingResults)
        }

        block.getBody.deploys.foldLeft(deploys) {
          case (acc, pd) => acc + (pd.getDeploy -> metadata(pd, acc))
        }
      }
    )

  override def getProcessingResults(hash: ByteString): F[List[(BlockHash, Block.ProcessedDeploy)]] =
    deploysWithMetadataRef.get.map(_.collect {
      case (d, Metadata(_, _, _, processingResults)) if d.deployHash == hash => processingResults
    }.toList.flatten)

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
        .map(
          d => (d, Metadata(status, now, now, Nil))
        )
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
        case (deploy, Metadata(`ProcessedStatusCode`, _, _, _))
            if hashes.toSet(deploy.deployHash) =>
          false
        case _ => true
      })
    )

  override def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]): F[Unit] =
    logOperation(
      s"markAsDiscarded(hashes = ${hashesToString(hashesAndReasons.map(_._1))})",
      setStatus(hashesAndReasons.map(_._1), DiscardedStatusCode, PendingStatusCode)
    )

  private def setStatus(hashes: List[ByteString], newStatus: Int, prevStatus: Int): F[Unit] =
    deploysWithMetadataRef.update(_.map {
      case (deploy, Metadata(`prevStatus`, _, createdAt, processingResults))
          if hashes.toSet(deploy.deployHash) =>
        (deploy, Metadata(newStatus, now, createdAt, processingResults))
      case x => x
    }) >> logCurrentState()

  override def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit] =
    logOperation(
      s"markAsDiscarded(expirationPeriod = ${expirationPeriod.toCoarsest.toString()})",
      deploysWithMetadataRef.update(_.map {
        case (deploy, Metadata(`PendingStatusCode`, updatedAt, createdAt, processingResults))
            if updatedAt < now - expirationPeriod.toMillis =>
          (
            deploy,
            Metadata(DiscardedStatusCode, now, createdAt, processingResults)
          )
        case x => x
      })
    )

  override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] = {
    val f: ((Deploy, Metadata)) => Boolean = (_: (Deploy, Metadata)) match {
      case (_, Metadata(`DiscardedStatusCode`, updatedAt, _, _))
          if updatedAt < now - expirationPeriod.toMillis =>
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

  override def readAccountPendingOldest(): fs2.Stream[F, Deploy] =
    fs2.Stream
      .eval(
        readPending.map(
          _.groupBy(_.getHeader.accountPublicKey)
            .mapValues(_.minBy(_.getHeader.timestamp))
            .values
            .toList
        )
      )
      .flatMap(deploys => fs2.Stream.fromIterator(deploys.toIterator))

  override def getByHashes(l: List[ByteString]): F[List[Deploy]] = {
    val hashesSet = l.toSet
    (readPending, readProcessed).mapN(_ ++ _).map(_.filter(d => hashesSet.contains(d.deployHash)))
  }

  private def readByStatus(status: Int): F[List[Deploy]] =
    deploysWithMetadataRef.get.map(_.collect {
      case (deploy, Metadata(`status`, _, _, _)) => deploy
    }.toList)

  override def sizePendingOrProcessed(): F[Long] =
    (readProcessed, readPending).mapN(_.size + _.size).map(_.toLong)

  override def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]] =
    deploysWithMetadataRef.get.map(_.collectFirst {
      case (d, Metadata(`PendingStatusCode` | `ProcessedStatusCode`, _, _, _))
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
      case 3 => "execution error"
      case 4 => "execution success"
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

  private def blockHashToString(b: Block): String = Base16.encode(b.blockHash.toByteArray).take(4)
  private def processedDeploysToString(b: Block): String =
    b.getBody.deploys.map(pd => deployToString(pd.getDeploy)).mkString(", ")

  private def now = System.currentTimeMillis()
}

object MockDeployStorage {
  case class Metadata(
      status: Int,
      updatedAt: Long,
      createdAt: Long,
      processingResults: List[(ByteString, Block.ProcessedDeploy)]
  )

  def create[F[_]: Sync: Log](): F[DeployStorage[F]] =
    for {
      ref <- Ref.of[F, Map[Deploy, Metadata]](Map.empty)
    } yield new MockDeployStorage[F](ref): DeployStorage[F]

  def unsafeCreate[F[_]: Sync: Log](): DeployStorage[F] =
    new MockDeployStorage[F](Ref.unsafe[F, Map[Deploy, Metadata]](Map.empty))
}
