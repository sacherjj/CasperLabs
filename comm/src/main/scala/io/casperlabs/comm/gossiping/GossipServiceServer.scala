package io.casperlabs.comm.gossiping

import cats.Parallel
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm.ServiceError.NotFound
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.downloadmanager.{BlockDownloadManager, DeployDownloadManager}
import io.casperlabs.comm.gossiping.synchronization.Synchronizer
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.DeployImplicits._
import io.casperlabs.shared.{Compression, Log}
import monix.tail.Iterant

import scala.collection.mutable.PriorityQueue
import scala.util.control.NonFatal

/** Server side implementation talking to the rest of the node such as casper, storage, download manager. */
class GossipServiceServer[F[_]: Concurrent: Parallel: Log: Metrics](
    backend: GossipServiceServer.Backend[F],
    synchronizer: Synchronizer[F],
    connector: GossipService.Connector[F],
    deployDownloadManager: DeployDownloadManager[F],
    blockDownloadManager: BlockDownloadManager[F],
    genesisApprover: GenesisApprover[F],
    maxChunkSize: Int,
    deployGossipEnabled: Boolean,
    blockDownloadSemaphore: Semaphore[F]
) extends GossipService[F] {
  import GossipServiceServer._

  case class NewBlocksInfo(
      blockHash: ByteString,
      isScheduled: Boolean,
      isDownloaded: Boolean
  )

  //TODO: Rate limit here as well?
  def newDeploys(request: NewDeploysRequest): F[NewDeploysResponse] =
    // Filter out the deploys which we don't have yet;
    // reply about those that we are going to download and relay them,
    // then schedule the downloads.
    request.deployHashes.distinct.toList
      .filterA { deployHash =>
        backend.hasDeploy(deployHash).map(!_)
      }
      .flatMap { newDeployHashes =>
        if (newDeployHashes.isEmpty) {
          NewDeploysResponse(isNew = false).pure[F]
        } else {
          for {
            remoteService <- connector(request.getSender)
            // TODO: Defend against malicious nodes similar to Synchronizer
            // Runs in background asynchronously
            _ <- (remoteService
                  .streamDeploySummaries(
                    StreamDeploySummariesRequest(newDeployHashes)
                  )
                  .toListL >>= { deploySummaries =>
                  deploySummaries
                    .traverse(
                      deploySummary =>
                        deployDownloadManager
                          .scheduleDownload(deploySummary, request.getSender, relay = true)
                    )
                }).forkAndLog.whenA(deployGossipEnabled)
          } yield NewDeploysResponse(isNew = true)
        }
      }

  //TODO: Rate limit here as well?
  override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
    // Filter out the blocks which we don't have yet;
    // reply about those that we are going to download and relay them,
    // then asynchronously sync the DAG, and schedule the downloads.
    newBlocks(
      request,
      skipRelaying = false,
      (syncOpt, response) => syncOpt.fold(().pure[F])(_.start.void).as(response)
    )

  /* Same as 'newBlocks' but with synchronous semantics, needed for bootstrapping and some tests.
   * Return any error we encountered during the sync explicitly to make sure the caller handles it. */
  def newBlocksSynchronous(
      request: NewBlocksRequest,
      skipRelaying: Boolean
  ): F[Either[SyncError, NewBlocksResponse]] =
    newBlocks(
      request,
      skipRelaying,
      // Wait for the the returned handles. This is the assumed behaviour in Casper unit tests.
      (syncOpt, response) =>
        syncOpt.fold(response.asRight[SyncError].pure[F])(_.flatMap {
          case Left(error)    => error.asLeft[NewBlocksResponse].pure[F]
          case Right(waiters) => waiters.parTraverse(identity).as(response.asRight)
        })
    )

  /** Creates the syncing procedure if there are blocks missing,
    * then passes the sync to the caller so it can decide whether
    * it wants to run it in the background or foreground. */
  private def newBlocks[T](
      request: NewBlocksRequest,
      skipRelaying: Boolean,
      // Callback to switch between sync and async modes;
      // the option None is no syncing is required.
      start: (Option[F[Either[SyncError, Vector[WaitHandle[F]]]]], NewBlocksResponse) => F[T]
  ): F[T] =
    request.blockHashes.distinct.toList
      .traverse { blockHash =>
        for {
          isScheduled  <- blockDownloadManager.isScheduled(blockHash)
          isDownloaded <- if (isScheduled) false.pure[F] else backend.hasBlock(blockHash)
        } yield NewBlocksInfo(blockHash, isScheduled, isDownloaded)
      }
      .map(_.filterNot(_.isDownloaded))
      .flatMap { newBlocks =>
        if (newBlocks.isEmpty) {
          start(none, NewBlocksResponse(isNew = false))
        } else {
          start(
            sync(request.getSender, newBlocks.toSet, skipRelaying).some,
            NewBlocksResponse(isNew = true)
          )
        }
      }

  /** Synchronize and download any missing blocks to get to the new ones.
    * This method will in itself not block on the results, just return a
    * list of deferred handles that the caller can decide to wait upon,
    * which some uses cases do, but mostly we expect to let them complete
    * asynchronously in the background. */
  private def sync(
      source: Node,
      newBlocks: Set[NewBlocksInfo],
      skipRelaying: Boolean
  ): F[Either[SyncError, Vector[WaitHandle[F]]]] = {
    def logSyncError(syncError: SyncError) = {
      val prefix  = s"Failed to sync DAG, source: ${source.show -> "peer"}."
      val message = syncError.getMessage
      Log[F].warn(s"$prefix $message").as(syncError.asLeft[Vector[WaitHandle[F]]])
    }

    val trySync: F[Either[SyncError, Vector[WaitHandle[F]]]] = for {
      _ <- Log[F].debug(
            s"Received notification about ${newBlocks.size} new block(s) from ${source.show -> "peer"}: ${newBlocks
              .map(_.blockHash)
              .map(Utils.hex)
              .mkString(", ") -> "blocks"}"
          )

      newBlockHashes           = newBlocks.map(_.blockHash)
      (scheduled, unscheduled) = newBlocks.partition(_.isScheduled)

      // Add the source as alternatives for already scheduled items.
      oldWaiters <- scheduled
                     .map(_.blockHash)
                     .toVector
                     .traverse(blockDownloadManager.addSource(_, source))

      // Sync only what hasn't been scheduled yet.
      errorOrDag <- if (unscheduled.nonEmpty)
                     synchronizer.syncDag(
                       source = source,
                       targetBlockHashes = unscheduled.map(_.blockHash)
                     )
                   else Vector.empty[BlockSummary].asRight[Synchronizer.SyncError].pure[F]

      errorOrWaiters <- errorOrDag.fold(
                         syncError => logSyncError(syncError), { dag =>
                           Log[F].debug(
                             s"Synced ${dag.size} blocks with ${source.show -> "peer"}"
                           ) *>
                             dag.traverse { summary =>
                               blockDownloadManager.scheduleDownload(
                                 summary,
                                 source = source,
                                 relay = !skipRelaying && newBlockHashes(summary.blockHash)
                               )
                             } map { newWaiters =>
                             (oldWaiters ++ newWaiters).asRight[SyncError]
                           }
                         }
                       )
    } yield errorOrWaiters

    trySync.onError {
      case NonFatal(ex) =>
        Log[F].error(s"Could not synchronize with ${source.show -> "peer"}: $ex")
    }
  }

  override def streamAncestorBlockSummaries(
      request: StreamAncestorBlockSummariesRequest
  ): Iterant[F, BlockSummary] = {
    // We return known hashes but not their parents.
    val knownHashes = request.knownBlockHashes.toSet

    type Depth = Long

    // Depth restriction is to be able to periodically reassess targets,
    // to pass back hashes that still have missing dependencies, but not
    // the ones which have connected to the DAG of the caller.
    val canFollow: ((BlockSummary, Depth)) => Boolean = {
      case (_, depth) =>
        depth <= request.maxDepth || request.maxDepth == -1
    }

    // Take the highest rank first, then the lowest depth.
    implicit val ord: Ordering[(BlockSummary, Depth)] =
      Ordering.by {
        case (summary, depth) => (summary.getHeader.jRank, -depth)
      }

    // Yield blocks in j-rank based reverse topological order.
    def loop(
        queue: PriorityQueue[(BlockSummary, Depth)],
        // At what depth did we visit a block. It's possible, with multiple targets,
        // that we'll see it again at a shallower path and can go further into its
        // dependencies.
        visited: Map[ByteString, Depth]
    ): Iterant[F, BlockSummary] =
      if (queue.isEmpty)
        Iterant.empty
      else {
        queue.dequeue() match {
          case (summary, depth)
              if visited.contains(summary.blockHash) && visited(summary.blockHash) <= depth =>
            // We visited this block from a smaller distance-to-target, so we already enqueued all the dependencies.
            loop(queue, visited)

          case (summary, depth) =>
            // Maybe we visited this block from a longer distance-to-target; from this lower distance we can go deeper into dependencies.
            Iterant.liftF {
              val dependencies =
                if (knownHashes(summary.blockHash) || depth == request.maxDepth) Seq.empty
                else
                  summary.parentHashes ++ summary.justifications.map(_.latestBlockHash)

              dependencies.toList
                .traverse(backend.getBlockSummary)
                .map(_.flatten)
            } flatMap { deps =>
              // Only follow ancestors where the total j-Rank distance is within maxDepth.
              val follow = deps.map { dep =>
                val jdepth = summary.getHeader.jRank - dep.getHeader.jRank + depth
                dep -> jdepth
              } filter canFollow

              def rest = loop(queue ++ follow, visited.updated(summary.blockHash, depth))

              if (visited.contains(summary.blockHash)) rest else Iterant.pure(summary) ++ rest
            }
        }
      }

    Iterant.liftF {
      request.targetBlockHashes.toList.traverse(backend.getBlockSummary).map { ss =>
        PriorityQueue(ss.flatten.map(_ -> 0L): _*) -> Map.empty[ByteString, Depth]
      }
    } flatMap {
      case (queue, visited) => loop(queue, visited)
    }
  }

  override def streamLatestMessages(
      request: StreamLatestMessagesRequest
  ): Iterant[F, Block.Justification] =
    Iterant.liftF(backend.latestMessages).flatMap(set => Iterant.fromSeq(set.toSeq))

  override def streamBlockSummaries(
      request: StreamBlockSummariesRequest
  ): Iterant[F, BlockSummary] =
    Iterant[F]
      .fromSeq(request.blockHashes)
      .mapEval(backend.getBlockSummary)
      .flatMap(Iterant.fromIterable(_))

  override def streamDeploySummaries(
      request: StreamDeploySummariesRequest
  ): Iterant[F, DeploySummary] =
    Iterant[F]
      .fromSeq(request.deployHashes)
      .mapEval(backend.getDeploySummary)
      .flatMap(Iterant.fromIterable(_))

  override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
    Iterant.resource(blockDownloadSemaphore.acquire)(
      _ => blockDownloadSemaphore.release
    ) flatMap { _ =>
      Iterant.liftF {
        backend.getBlock(
          request.blockHash,
          request.excludeDeployBodies || request.onlyIncludeDeployHashes
        )
      } flatMap {
        case Some(block) =>
          val it = chunkIt(
            (if (request.onlyIncludeDeployHashes) block.clearDeploysExceptHash else block).toByteArray,
            effectiveChunkSize(request.chunkSize),
            request.acceptedCompressionAlgorithms
          )
          Iterant.fromIterator(it)

        case None =>
          Iterant.raiseError(NotFound.block(request.blockHash))
      }
    }

  override def streamDeploysChunked(request: StreamDeploysChunkedRequest): Iterant[F, Chunk] = {
    val chunkSize = effectiveChunkSize(request.chunkSize)
    backend.getDeploys(request.deployHashes.toSet).flatMap { deploy =>
      val it = chunkIt(deploy.toByteArray, chunkSize, request.acceptedCompressionAlgorithms)
      Iterant.fromIterator(it)
    }
  }

  override def getGenesisCandidate(request: GetGenesisCandidateRequest): F[GenesisCandidate] =
    rethrow(genesisApprover.getCandidate)

  override def addApproval(request: AddApprovalRequest): F[Unit] =
    rethrow(genesisApprover.addApproval(request.blockHash, request.getApproval)).void

  override def streamDagSliceBlockSummaries(
      request: StreamDagSliceBlockSummariesRequest
  ): Iterant[F, BlockSummary] =
    backend.dagTopoSort(request.startRank.toLong, request.endRank.toLong)

  private def effectiveChunkSize(chunkSize: Int): Int =
    if (0 < chunkSize && chunkSize < maxChunkSize) chunkSize
    else maxChunkSize

  // MonadError isn't covariant in the error type.
  private def rethrow[E <: Throwable, A](value: F[Either[E, A]]): F[A] =
    Sync[F].rethrow(value.widen)
}

object GossipServiceServer {
  type Compressor = Array[Byte] => Array[Byte]

  val compressors: Map[String, Compressor] = Map(
    "lz4" -> Compression.compress
  )

  def chunkIt(
      data: Array[Byte],
      chunkSize: Int,
      acceptedCompressionAlgorithms: Seq[String]
  ): Iterator[Chunk] = {
    val (alg, content) = acceptedCompressionAlgorithms.map(_.toLowerCase).collectFirst {
      case alg if compressors contains alg =>
        alg -> compressors(alg)(data)
    } getOrElse {
      "" -> data
    }

    val header = Chunk.Header(
      compressionAlgorithm = alg,
      // Sending the final length so the receiver knows how many chunks they are going to get.
      contentLength = content.length,
      // Sending the original length needed for decompression (at least the one we have now).
      originalContentLength = data.length
    )

    val chunks = content.sliding(chunkSize, chunkSize).map { arr =>
      Chunk().withData(ByteString.copyFrom(arr))
    }

    Iterator(Chunk().withHeader(header)) ++ chunks
  }

  /** Interface to storage and consensus. */
  trait Backend[F[_]] {
    def getDeploySummary(deployHash: ByteString): F[Option[DeploySummary]]
    def hasBlock(blockHash: ByteString): F[Boolean]
    def hasDeploy(deployHash: ByteString): F[Boolean]
    def getBlockSummary(blockHash: ByteString): F[Option[BlockSummary]]
    def getBlock(blockHash: ByteString, deploysBodiesExcluded: Boolean): F[Option[Block]]
    def getDeploys(deployHashes: Set[ByteString]): Iterant[F, Deploy]

    /** Returns latest messages as seen currently by the node.
      * NOTE: In the future we will remove redundant messages. */
    def latestMessages: F[Set[Block.Justification]]

    /* Retrieve the DAG slice in topological order, inclusive */
    def dagTopoSort(startRank: Long, endRank: Long): Iterant[F, BlockSummary]

  }

  def apply[F[_]: Concurrent: Parallel: Log: Metrics](
      backend: GossipServiceServer.Backend[F],
      synchronizer: Synchronizer[F],
      connector: GossipService.Connector[F],
      deployDownloadManager: DeployDownloadManager[F],
      blockDownloadManager: BlockDownloadManager[F],
      genesisApprover: GenesisApprover[F],
      maxChunkSize: Int,
      maxParallelBlockDownloads: Int,
      deployGossipEnabled: Boolean
  ): F[GossipServiceServer[F]] =
    for {
      blockDownloadSemaphore <- Semaphore[F](maxParallelBlockDownloads.toLong)
    } yield new GossipServiceServer(
      backend,
      synchronizer,
      connector,
      deployDownloadManager,
      blockDownloadManager,
      genesisApprover,
      maxChunkSize,
      deployGossipEnabled,
      blockDownloadSemaphore
    )
}
