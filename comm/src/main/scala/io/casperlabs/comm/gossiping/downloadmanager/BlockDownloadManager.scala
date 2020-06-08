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
import scala.util.Try
import scala.concurrent.duration.FiniteDuration

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

  trait Backend[F[_]] extends super.Backend[F] {
    def readDeploys(deployHashes: Seq[ByteString]): F[List[Deploy]]
  }

  override implicit val metricsSource: Metrics.Source =
    Metrics.Source(BlockGossipingMetricsSource, "DownloadManager")

  /** Start the download manager. */
  def apply[F[_]: ContextShift: Concurrent: Log: Timer: Metrics](
      maxParallelDownloads: Int,
      partialBlocksEnabled: Boolean,
      cacheExpiry: FiniteDuration,
      connectToGossip: GossipService.Connector[F],
      backend: Backend[F],
      relaying: BlockRelaying[F],
      retriesConf: RetriesConf,
      egressScheduler: Scheduler
  ): Resource[F, BlockDownloadManager[F]] =
    Resource.make {
      for {
        isShutdown  <- Ref.of(false)
        itemsRef    <- Ref.of(Map.empty[ByteString, Item[F]])
        workersRef  <- Ref.of(Map.empty[ByteString, Fiber[F, Unit]])
        semaphore   <- Semaphore[F](maxParallelDownloads.toLong)
        signal      <- MVar[F].empty[Signal[F]]
        recentCache <- DownloadedCache[F, ByteString](cacheExpiry)
        manager = new BlockDownloadManagerImpl[F](
          this,
          isShutdown,
          itemsRef,
          workersRef,
          recentCache,
          semaphore,
          signal,
          connectToGossip,
          backend,
          relaying,
          retriesConf,
          egressScheduler,
          partialBlocksEnabled
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
    (summary.parentHashes ++ summary.justifications.map(_.latestBlockHash)).distinct
}

class BlockDownloadManagerImpl[F[_]](
    val companion: BlockDownloadManagerImpl.type,
    val isShutdown: Ref[F, Boolean],
    // Keep track of active downloads and dependencies.
    val itemsRef: Ref[F, Map[ByteString, BlockDownloadManagerImpl.Item[F]]],
    // Keep track of ongoing downloads so we can cancel them.
    val workersRef: Ref[F, Map[ByteString, Fiber[F, Unit]]],
    // Keep a cache of recently downloaded items.
    val recentlyDownloaded: BlockDownloadManagerImpl.DownloadedCache[F, ByteString],
    // Limit parallel downloads.
    val semaphore: Semaphore[F],
    // Single item control signals for the manager loop.
    val signal: MVar[F, BlockDownloadManagerImpl.Signal[F]],
    // Establish gRPC connection to another node.
    val connectToGossip: GossipService.Connector[F],
    val backend: BlockDownloadManagerImpl.Backend[F],
    val relaying: BlockRelaying[F],
    val retriesConf: RetriesConf,
    val egressScheduler: Scheduler,
    partialBlocksEnabled: Boolean
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

  override def tryParseDownloadable(bytes: Array[Byte]) =
    Try(Block.parseFrom(bytes))

  /**
    * 1. Downloads a partial block without deploy bodies.
    * 2. Downloads missing deploys from the same peer.
    * 3. Restores full block by combining downloaded deploys and existing ones from the database.
    */
  override protected def fetchAndRestore(source: Node, summary: BlockSummary): F[Block] =
    if (summary.getHeader.deployCount == 0) {
      Block()
        .withBlockHash(summary.blockHash)
        .withHeader(summary.getHeader)
        .withSignature(summary.getSignature)
        .withBody(Block.Body())
        .pure[F]
    } else {
      for {
        partialBlock         <- super.fetchAndRestore(source, summary)
        fullDeploys          = partialBlock.getBody.deploys.map(_.getDeploy).filter(_.body.nonEmpty).toList
        fullDeployHashes     = fullDeploys.map(_.deployHash).toSet
        allDeployHashes      = partialBlock.getBody.deploys.map(_.getDeploy.deployHash).toSet
        existingDeploys      <- backend.readDeploys((allDeployHashes diff fullDeployHashes).toList)
        existingDeployHashes = existingDeploys.map(_.deployHash).toSet
        missingDeployHashes  = allDeployHashes diff fullDeployHashes diff existingDeployHashes
        missingDeploys       <- fetchAndRestoreDeploys(source, missingDeployHashes)
        fullBlock            = partialBlock.withDeploys(fullDeploys ++ existingDeploys ++ missingDeploys)
      } yield fullBlock
    }

  /** Stream the chunks of a block without deploy bodies. */
  override def fetch(
      source: Node,
      blockHash: ByteString
  ): Iterant[F, Chunk] = {
    val itF = connectToGossip(source).map { stub =>
      val req = GetBlockChunkedRequest(
        blockHash = blockHash,
        acceptedCompressionAlgorithms = Seq("lz4"),
        onlyIncludeDeployHashes = partialBlocksEnabled
      )
      stub.getBlockChunked(req)
    }
    Iterant.liftF(itF).flatten
  }

  /** Ask for all deploys from the source in a stream of chunks alternating headers and content. */
  private def fetchDeploys(source: Node, deployHashes: Seq[ByteString]): Iterant[F, Chunk] = {
    val itF = connectToGossip(source).map { stub =>
      val req = StreamDeploysChunkedRequest(
        deployHashes = deployHashes,
        acceptedCompressionAlgorithms = Seq("lz4")
      )
      stub.streamDeploysChunked(req)
    }
    Iterant.liftF(itF).flatten
  }

  /** Restore individual deploys from a stream of altnating header and content chunks. */
  private def fetchAndRestoreDeploys(
      source: Node,
      deployHashes: Set[ByteString]
  ): F[List[Deploy]] = {

    def parseBuffered(acc: DeploysAcc): F[DeploysAcc] =
      for {
        bytes  <- restore(source, Iterant.fromList(acc.chunks.reverse))
        deploy <- Sync[F].delay(Deploy.parseFrom(bytes))
        _ <- Sync[F]
              .raiseError(invalidChunks(source, "Duplicate deploy in stream."))
              .whenA(acc.deployHashes(deploy.deployHash))
        _ <- Sync[F]
              .raiseError(invalidChunks(source, "Unexpected deploy in stream."))
              .whenA(!deployHashes(deploy.deployHash))
      } yield acc.copy(
        deploys = deploy :: acc.deploys,
        deployHashes = acc.deployHashes + deploy.deployHash,
        chunks = Nil
      )

    fetchDeploys(source, deployHashes.toList)
      .foldWhileLeftEvalL(DeploysAcc(Nil, Set.empty, Nil).pure[F]) {
        case (acc, chunk) if chunk.content.isHeader && acc.chunks.nonEmpty =>
          // Try to restore whatever we have accumulated so far, to see if it's legit.
          parseBuffered(acc).map(_.copy(chunks = List(chunk)).asLeft[DeploysAcc])
        case (acc, chunk) =>
          acc.copy(chunks = chunk :: acc.chunks).asLeft[DeploysAcc].pure[F]
      } flatMap {
      case acc if acc.chunks.nonEmpty =>
        // Try to restore the last set of chunks. There should always be one that doesn't end with a header.
        parseBuffered(acc)
      case acc =>
        acc.pure[F]
    } map (_.deploys.reverse)
  }

  case class DeploysAcc(
      deploys: List[Deploy],
      deployHashes: Set[ByteString],
      chunks: List[Chunk]
  )
}
