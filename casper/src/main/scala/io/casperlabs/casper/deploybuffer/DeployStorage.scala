package io.casperlabs.casper.deploybuffer

import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{Block, Deploy}
import simulacrum.typeclass

import scala.concurrent.duration._

@typeclass trait DeployStorageWriter[F[_]] {
  def addAsExecuted(block: Block): F[Unit]

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
@typeclass trait DeployStorageReader[F[_]] {
  def readProcessed: F[List[Deploy]]

  def readProcessedByAccount(account: ByteString): F[List[Deploy]]

  def readProcessedHashes: F[List[ByteString]]

  def readPending: F[List[Deploy]]

  def readPendingHashes: F[List[ByteString]]

  def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]]

  def sizePendingOrProcessed(): F[Long]

  def getByHashes(l: List[ByteString]): F[List[Deploy]]

  /** @return List of blockHashes and processing results */
  def getProcessingResults(hash: ByteString): F[List[(BlockHash, ProcessedDeploy)]]
}

@typeclass trait DeployStorage[F[_]] extends DeployStorageWriter[F] with DeployStorageReader[F] {}

object DeployStorage {
  implicit def deriveDeployStorage[F[_]](
      implicit writer: DeployStorageWriter[F],
      reader: DeployStorageReader[F]
  ): DeployStorage[F] = new DeployStorage[F] {
    override def addAsExecuted(block: Block): F[Unit] =
      writer.addAsExecuted(block)

    override def addAsPending(deploys: List[Deploy]): F[Unit] =
      writer.addAsPending(deploys)

    override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
      writer.addAsProcessed(deploys)

    override def markAsProcessedByHashes(hashes: List[BlockHash]): F[Unit] =
      writer.markAsProcessedByHashes(hashes)

    override def markAsPendingByHashes(hashes: List[BlockHash]): F[Unit] =
      writer.markAsPendingByHashes(hashes)

    override def markAsFinalizedByHashes(hashes: List[BlockHash]): F[Unit] =
      writer.markAsFinalizedByHashes(hashes)

    override def markAsDiscardedByHashes(hashesAndReasons: List[(BlockHash, String)]): F[Unit] =
      writer.markAsDiscardedByHashes(hashesAndReasons)

    override def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit] =
      writer.markAsDiscarded(expirationPeriod)

    override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] =
      writer.cleanupDiscarded(expirationPeriod)

    override def readProcessed: F[List[Deploy]] =
      reader.readProcessed

    override def readProcessedByAccount(account: BlockHash): F[List[Deploy]] =
      reader.readProcessedByAccount(account)

    override def readProcessedHashes: F[List[BlockHash]] =
      reader.readProcessedHashes

    override def readPending: F[List[Deploy]] =
      reader.readPending

    override def readPendingHashes: F[List[BlockHash]] =
      reader.readPendingHashes

    override def getPendingOrProcessed(hash: BlockHash): F[Option[Deploy]] =
      reader.getPendingOrProcessed(hash)

    override def sizePendingOrProcessed(): F[Long] =
      reader.sizePendingOrProcessed()

    override def getByHashes(l: List[BlockHash]): F[List[Deploy]] = reader.getByHashes(l)

    override def getProcessingResults(hash: BlockHash): F[List[(BlockHash, ProcessedDeploy)]] =
      reader.getProcessingResults(hash)
  }
}
