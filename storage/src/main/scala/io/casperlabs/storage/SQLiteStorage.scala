package io.casperlabs.storage

import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import doobie.util.transactor.Transactor
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Time
import io.casperlabs.storage.block.BlockStorage.{BlockHash, BlockHashPrefix, DeployHash}
import io.casperlabs.storage.block.{BlockStorage, SQLiteBlockStorage}
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage, SQLiteDagStorage}
import io.casperlabs.storage.deploy.{DeployStorage, SQLiteDeployStorage}
import fs2._

import scala.concurrent.duration.FiniteDuration

object SQLiteStorage {
  def create[F[_]: Sync: Transactor: Metrics: Time](
      deployStorageChunkSize: Int = 100,
      wrap: BlockStorage[F] => F[BlockStorage[F]]
  ): F[BlockStorage[F] with DagStorage[F] with DeployStorage[F]] =
    for {
      blockStorage  <- SQLiteBlockStorage.create[F] >>= wrap
      dagStorage    <- SQLiteDagStorage.create[F]
      deployStorage <- SQLiteDeployStorage.create[F](deployStorageChunkSize)
    } yield new BlockStorage[F] with DagStorage[F] with DeployStorage[F] {

      override def addAsExecuted(block: Block): F[Unit] =
        deployStorage.addAsExecuted(block)

      override def addAsPending(deploys: List[Deploy]): F[Unit] =
        deployStorage.addAsPending(deploys)

      override def addAsProcessed(deploys: List[Deploy]): F[Unit] =
        deployStorage.addAsProcessed(deploys)

      override def markAsProcessedByHashes(hashes: List[ByteString]): F[Unit] =
        deployStorage.markAsProcessedByHashes(hashes)

      override def markAsPendingByHashes(hashes: List[ByteString]): F[Unit] =
        deployStorage.markAsPendingByHashes(hashes)

      override def markAsFinalizedByHashes(hashes: List[ByteString]): F[Unit] =
        deployStorage.markAsFinalizedByHashes(hashes)

      override def markAsDiscardedByHashes(hashesAndReasons: List[(ByteString, String)]): F[Unit] =
        deployStorage.markAsDiscardedByHashes(hashesAndReasons)

      override def markAsDiscarded(expirationPeriod: FiniteDuration): F[Unit] =
        deployStorage.markAsDiscarded(expirationPeriod)

      override def cleanupDiscarded(expirationPeriod: FiniteDuration): F[Int] =
        deployStorage.cleanupDiscarded(expirationPeriod)

      override def readProcessed: F[List[Deploy]] = deployStorage.readProcessed

      override def readProcessedByAccount(account: ByteString): F[List[Deploy]] =
        deployStorage.readProcessedByAccount(account)

      override def readProcessedHashes: F[List[ByteString]] = deployStorage.readProcessedHashes

      override def readPending: F[List[Deploy]] = deployStorage.readPending

      override def readPendingHashes: F[List[ByteString]] = deployStorage.readPendingHashes

      override def getPendingOrProcessed(hash: ByteString): F[Option[Deploy]] =
        deployStorage.getPendingOrProcessed(hash)

      override def sizePendingOrProcessed(): F[Long] = deployStorage.sizePendingOrProcessed()

      override def getByHashes(l: Set[ByteString]): Stream[F, Deploy] = deployStorage.getByHashes(l)

      override def getProcessingResults(
          hash: ByteString
      ): F[List[(BlockHash, Block.ProcessedDeploy)]] = deployStorage.getProcessingResults(hash)

      override def readAccountPendingOldest(): fs2.Stream[F, Deploy] =
        deployStorage.readAccountPendingOldest()

      override def readAccountLowestNonce(): fs2.Stream[F, DeployHash] =
        deployStorage.readAccountLowestNonce()

      override def getRepresentation: F[DagRepresentation[F]] = dagStorage.getRepresentation

      override def insert(block: Block): F[DagRepresentation[F]] =
        dagStorage.insert(block)

      override def checkpoint(): F[Unit] = dagStorage.checkpoint()

      override def clear(): F[Unit] =
        for {
          _ <- deployStorage.clear()
          _ <- dagStorage.clear()
          _ <- blockStorage.clear()
        } yield ()

      override def close(): F[Unit] =
        for {
          _ <- deployStorage.close()
          _ <- dagStorage.close()
          _ <- blockStorage.close()
        } yield ()

      override def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
        blockStorage.get(blockHash)

      override def getByPrefix(blockHashPrefix: BlockHashPrefix): F[Option[BlockMsgWithTransform]] =
        blockStorage.getByPrefix(blockHashPrefix)

      override def isEmpty: F[Boolean] = blockStorage.isEmpty

      override def getSummaryByPrefix(blockHashPrefix: BlockHashPrefix): F[Option[BlockSummary]] =
        blockStorage.getSummaryByPrefix(blockHashPrefix)

      override def put(
          blockHash: BlockHash,
          blockMsgWithTransform: BlockMsgWithTransform
      ): F[Unit] =
        for {
          _ <- blockMsgWithTransform.blockMessage.fold(().pure[F])(
                b => deployStorage.addAsExecuted(b) >> dagStorage.insert(b).void
              )
          _ <- blockStorage.put(blockHash, blockMsgWithTransform)
        } yield ()

      override def getApprovedBlock(): F[Option[ApprovedBlock]] = blockStorage.getApprovedBlock()

      override def putApprovedBlock(block: ApprovedBlock): F[Unit] =
        blockStorage.putApprovedBlock(block)

      override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
        blockStorage.getBlockSummary(blockHash)

      override def findBlockHashesWithDeployhash(deployHash: ByteString): F[Seq[BlockHash]] =
        blockStorage.findBlockHashesWithDeployhash(deployHash)
    }
}
