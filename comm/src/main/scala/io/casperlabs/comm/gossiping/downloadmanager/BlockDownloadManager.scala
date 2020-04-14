package io.casperlabs.comm.gossiping.downloadmanager

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.downloadmanager.BlockDownloadManagerImpl.RetriesConf
import io.casperlabs.comm.gossiping.relaying.BlockRelaying
import io.casperlabs.crypto.codec.ByteArraySyntax
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.DeployImplicits._
import io.casperlabs.shared.Log
import monix.execution.Scheduler
import monix.tail.Iterant

/** Manages the download, validation, storing and gossiping of blocks. */
trait BlockDownloadManager[F[_]] extends DownloadManager[F] {
  override type Handle       = BlockSummary
  override type Identifier   = ByteString
  override type Downloadable = Block
}

object BlockDownloadManagerImpl extends DownloadManagerCompanion {
  override type Handle       = BlockSummary
  override type Identifier   = ByteString
  override type Downloadable = Block

  trait EnrichedBackend[F[_]] extends Backend[F] {
    def readDeploys(deployHashes: Seq[ByteString]): F[List[Deploy]]
  }

  override implicit val metricsSource: Metrics.Source =
    Metrics.Source(BlockGossipingMetricsSource, "DownloadManager")

  /** Start the download manager. */
  def apply[F[_]: ContextShift: Concurrent: Log: Timer: Metrics](
      maxParallelDownloads: Int,
      connectToGossip: GossipService.Connector[F],
      backend: EnrichedBackend[F],
      relaying: BlockRelaying[F],
      retriesConf: RetriesConf,
      egressScheduler: Scheduler
  ): Resource[F, BlockDownloadManager[F]] =
    Resource.make {
      for {
        isShutdown <- Ref.of(false)
        itemsRef   <- Ref.of(Map.empty[ByteString, Item[F]])
        workersRef <- Ref.of(Map.empty[ByteString, Fiber[F, Unit]])
        semaphore  <- Semaphore[F](maxParallelDownloads.toLong)
        signal     <- MVar[F].empty[Signal[F]]
        manager = new BlockDownloadManagerImpl[F](
          this,
          isShutdown,
          itemsRef,
          workersRef,
          semaphore,
          signal,
          connectToGossip,
          backend,
          relaying,
          retriesConf,
          egressScheduler
        )
        managerLoop <- manager.run.start
      } yield (isShutdown, workersRef, managerLoop, manager)
    } {
      case (isShutdown, workersRef, managerLoop, _) =>
        for {
          _       <- Log[F].info("Shutting down the Block Download Manager...")
          _       <- isShutdown.set(true)
          _       <- managerLoop.cancel.attempt
          workers <- workersRef.get
          _       <- workers.values.toList.traverse(_.cancel.attempt)
        } yield ()
    } map {
      case (_, _, _, manager) => manager: BlockDownloadManager[F]
    }

  override def dependencies(summary: BlockSummary) =
    summary.parentHashes ++ summary.justifications.map(_.latestBlockHash)
}

class BlockDownloadManagerImpl[F[_]](
    val companion: BlockDownloadManagerImpl.type,
    val isShutdown: Ref[F, Boolean],
    // Keep track of active downloads and dependencies.
    val itemsRef: Ref[F, Map[ByteString, BlockDownloadManagerImpl.Item[F]]],
    // Keep track of ongoing downloads so we can cancel them.
    val workersRef: Ref[F, Map[ByteString, Fiber[F, Unit]]],
    // Limit parallel downloads.
    val semaphore: Semaphore[F],
    // Single item control signals for the manager loop.
    val signal: MVar[F, BlockDownloadManagerImpl.Signal[F]],
    // Establish gRPC connection to another node.
    val connectToGossip: GossipService.Connector[F],
    val backend: BlockDownloadManagerImpl.EnrichedBackend[F],
    val relaying: BlockRelaying[F],
    val retriesConf: RetriesConf,
    val egressScheduler: Scheduler
)(
    implicit
    override val H: ContextShift[F],
    override val C: Concurrent[F],
    override val T: Timer[F],
    override val L: Log[F],
    override val M: Metrics[F]
) extends BlockDownloadManager[F]
    with DownloadManagerImpl[F] {
  override val kind                       = "block"
  override implicit val E: Eq[Identifier] = Eq.instance((a: ByteString, b: ByteString) => a == b)
  override implicit val S: Show[Identifier] =
    Show.show((bs: ByteString) => bs.toByteArray.base16Encode)

  override def toByteString(id: ByteString) = id

  override def extractIdFromHandle(summary: BlockSummary) = summary.blockHash

  override def extractIdFromDownloadable(block: Block) = block.blockHash

  /**
    * 1. Downloads a partial block without deploy bodies.
    * 2. Downloads missing deploys from the same peer.
    * 3. Restores full block by combining downloaded deploys and existing ones from the database.
    *
    * TODO: Quick and dirty solution possibly causes performance penalty.
    *       Reads existing deploys from the database only for writing them back wasting storage I/O resources.
    *       Decided to go this way because of the deadline, must be optimized later.
    *       Optimization will cause significant and complex changes:
    *       a) Validation of a partial block
    *       b) Storing the partial block
    *       c) Filling the partial block with missing deploys
    *       *) Invalidation of the partial block and removing from the database if it hasn't been fully filled after some time.
    */
  override protected def doFetchAndRestore(source: Node, blockHash: ByteString): F[Block] =
    for {
      blockBytes          <- fetch(source, streamBlock(source, blockHash))
      partialBlock        <- Sync[F].delay(Block.parseFrom(blockBytes))
      _                   <- checkId(source, partialBlock, blockHash)
      allDeployHashes     = partialBlock.getBody.deploys.map(_.getDeploy.deployHash)
      hash2Index          = allDeployHashes.zipWithIndex.toMap
      existingDeploys     <- backend.readDeploys(allDeployHashes)
      missingDeployHashes = allDeployHashes.diff(existingDeploys.map(_.deployHash))
      deploysBytes        <- fetch(source, streamDeploys(source, missingDeployHashes))
      missingDeploys      <- Sync[F].delay(DeploysList.parseFrom(deploysBytes).deploys)
      allDeploys          = (existingDeploys ++ missingDeploys).sortBy(d => hash2Index(d.deployHash))
      fullBlock           = partialBlock.withDeploys(allDeploys)
    } yield fullBlock

  private def streamBlock(
      source: Node,
      blockHash: ByteString
  ): Iterant[F, Chunk] = {
    val itF = connectToGossip(source).map { stub =>
      val req = GetBlockChunkedRequest(
        blockHash = blockHash,
        acceptedCompressionAlgorithms = Seq("lz4"),
        excludeDeployBodies = true
      )
      stub.getBlockChunked(req)
    }
    Iterant.liftF(itF).flatten
  }

  private def streamDeploys(source: Node, deployHashes: Seq[ByteString]): Iterant[F, Chunk] = {
    val itF = connectToGossip(source).map { stub =>
      val req = StreamDeploysChunkedRequest(
        deployHashes = deployHashes,
        acceptedCompressionAlgorithms = Seq("lz4")
      )
      stub.streamDeploysChunked(req)
    }
    Iterant.liftF(itF).flatten
  }

  override def streamChunks(source: Node, blockHash: ByteString): Iterant[F, Chunk] =
    throw new IllegalArgumentException("Shouldn't be called")

  override def parseDownloadable(bytes: Array[Byte]) =
    throw new IllegalArgumentException("Shouldn't be called")
}
