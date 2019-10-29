package io.casperlabs.storage.deploy

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.crypto.Keys.PublicKeyBS
import io.casperlabs.storage.block.BlockStorage.{BlockHash, DeployHash}
import simulacrum.typeclass

import scala.concurrent.duration._
import cats.mtl.ApplicativeAsk

@typeclass trait DeployStorageWriter[F[_]] {
  private[storage] def addAsExecuted(block: Block): F[Unit]

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

  /** Deletes discarded deploys from buffer that are older than 'now - expirationPeriod'.
    * Won't delete bodies of deploys which were [[addAsExecuted]] before.
    * Otherwise all the data will be deleted.
    * @return Number of deleted deploys from buffer minus those who [[addAsExecuted]] */
  def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int]

  def clear(): F[Unit]

  def close(): F[Unit]
}
@typeclass trait DeployStorageReader[F[_]] {
  def readProcessed: F[List[Deploy]]

  def readProcessedByAccount(account: ByteString): F[List[Deploy]]

  def readProcessedHashes: F[List[DeployHash]]

  def readPending: F[List[Deploy]]

  def readPendingHashes: F[List[DeployHash]]

  def readPendingHeaders: F[List[Deploy.Header]]

  def readPendingHashesAndHeaders: fs2.Stream[F, (DeployHash, Deploy.Header)]

  def getPendingOrProcessed(deployHash: DeployHash): F[Option[Deploy]]

  def sizePendingOrProcessed(): F[Long]

  def getByHash(deployHash: DeployHash): F[Option[Deploy]]

  def getByHashes(deployHashes: Set[DeployHash]): fs2.Stream[F, Deploy]

  /** @return List of blockHashes and processing results in descendant order by execution time (block creation timestamp)*/
  def getProcessingResults(deployHash: DeployHash): F[List[(BlockHash, ProcessedDeploy)]]

  /** Read all the processsed deploys in a block. */
  def getProcessedDeploys(blockHash: BlockHash): F[List[ProcessedDeploy]]

  /** Read the status of a deploy from the buffer, if it's still in it. */
  def getBufferedStatus(deployHash: DeployHash): F[Option[DeployInfo.Status]]

  def getDeployInfo(deployHash: DeployHash): F[Option[DeployInfo]]

  def getDeployInfos(deploys: List[Deploy]): F[List[DeployInfo]]

  /** @return List of deploys created by specified account*/
  def getDeploysByAccount(
      account: PublicKeyBS,
      limit: Int,
      lastTimeStamp: Long,
      lastDeployHash: DeployHash
  ): F[List[Deploy]]
}

@typeclass trait DeployStorage[F[_]] {
  def writer: DeployStorageWriter[F]
  def reader(implicit dv: DeployInfo.View = DeployInfo.View.FULL): DeployStorageReader[F]
}

object DeployStorageWriter {
  implicit def fromStorage[F[_]](implicit ev: DeployStorage[F]): DeployStorageWriter[F] =
    ev.writer
}

object DeployStorageReader {
  implicit def fromStorage[F[_]](
      implicit ev: DeployStorage[F],
      dv: DeployInfo.View = DeployInfo.View.FULL
  ): DeployStorageReader[F] =
    ev.reader
}
