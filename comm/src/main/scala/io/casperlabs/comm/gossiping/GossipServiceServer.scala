package io.casperlabs.comm.gossiping

import cats.Parallel
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary, GenesisCandidate}
import io.casperlabs.comm.ServiceError.NotFound
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError
import io.casperlabs.comm.gossiping.synchronization.Synchronizer
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.shared.{Compression, Log}
import monix.tail.Iterant

import scala.collection.immutable.Queue
import scala.util.control.NonFatal

/** Server side implementation talking to the rest of the node such as casper, storage, download manager. */
class GossipServiceServer[F[_]: Concurrent: Parallel: Log: Metrics](
    backend: GossipServiceServer.Backend[F],
    synchronizer: Synchronizer[F],
    downloadManager: DownloadManager[F],
    genesisApprover: GenesisApprover[F],
    maxChunkSize: Int,
    blockDownloadSemaphore: Semaphore[F]
) extends GossipService[F] {
  import GossipServiceServer._

  //TODO: Rate limit here as well?
  override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
    // Collect the blocks which we don't have yet;
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
      .filterA { blockHash =>
        backend.hasBlock(blockHash).map(!_)
      }
      .flatMap { newBlockHashes =>
        if (newBlockHashes.isEmpty) {
          start(none, NewBlocksResponse(isNew = false))
        } else {
          start(
            sync(request.getSender, newBlockHashes.toSet, skipRelaying).some,
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
      newBlockHashes: Set[ByteString],
      skipRelaying: Boolean
  ): F[Either[SyncError, Vector[WaitHandle[F]]]] = {
    def logSyncError(syncError: SyncError) = {
      val prefix  = s"Failed to sync DAG, source: ${source.show -> "peer"}."
      val message = syncError.getMessage
      Log[F].warn(s"$prefix $message").as(syncError.asLeft)
    }

    val trySync: F[Either[SyncError, Vector[WaitHandle[F]]]] = for {
      _ <- Log[F].info(
            s"Received notification about ${newBlockHashes.size} new block(s) from ${source.show -> "peer"}: ${newBlockHashes
              .map(Utils.hex)
              .mkString(", ") -> "blocks"}"
          )
      errorOrDag <- synchronizer.syncDag(
                     source = source,
                     targetBlockHashes = newBlockHashes
                   )
      errorOrWaiters <- errorOrDag.fold(
                         syncError => logSyncError(syncError), { dag =>
                           Log[F].info(
                             s"Syncing ${dag.size} blocks with ${source.show -> "peer"}"
                           ) *>
                             dag.traverse { summary =>
                               downloadManager.scheduleDownload(
                                 summary,
                                 source = source,
                                 relay = !skipRelaying && newBlockHashes(summary.blockHash)
                               )
                             } map { waiters =>
                             waiters.asRight[SyncError]
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

    // Depth restriction is to be able to periodically reassess targets,
    // to pass back hashes that still have missing dependencies, but not
    // the ones which have connected to the DAG of the caller.
    def canGoDeeper(depth: Int) =
      depth < request.maxDepth || request.maxDepth == -1

    // Visit blocks in simple BFS order rather than try to establish topological sorting because BFS
    // is invariant in maximum depth, while topological sorting could depend on whether we traversed
    // backwards enough to re-join forks with different lengths of sub-paths.
    def loop(
        queue: Queue[(Int, ByteString)],
        visited: Set[ByteString]
    ): Iterant[F, BlockSummary] =
      if (queue.isEmpty)
        Iterant.empty
      else {
        queue.dequeue match {
          case ((_, blockHash), queue) if visited(blockHash) =>
            loop(queue, visited)

          case ((depth, blockHash), queue) =>
            Iterant.liftF(backend.getBlockSummary(blockHash)) flatMap {
              case None =>
                loop(queue, visited + blockHash)

              case Some(summary) =>
                val ancestors =
                  if (canGoDeeper(depth) && !knownHashes(summary.blockHash)) {
                    val ancestors =
                      summary.parentHashes ++
                        summary.justifications.map(_.latestBlockHash)

                    ancestors.map(depth + 1 -> _)
                  } else {
                    Seq.empty
                  }

                Iterant.pure(summary) ++ loop(queue ++ ancestors, visited + blockHash)
            }
        }
      }

    Iterant.delay {
      Queue(request.targetBlockHashes.map(0 -> _): _*) -> Set.empty[ByteString]
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
      .mapEval(backend.getBlockSummary(_))
      .flatMap(Iterant.fromIterable(_))

  override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
    Iterant.resource(blockDownloadSemaphore.acquire)(
      _ => blockDownloadSemaphore.release
    ) flatMap { _ =>
      Iterant.liftF {
        backend.getBlock(request.blockHash)
      } flatMap {
        case Some(block) =>
          val it = chunkIt(
            block.toByteArray,
            effectiveChunkSize(request.chunkSize),
            request.acceptedCompressionAlgorithms
          )

          Iterant.fromIterator(it)

        case None =>
          Iterant.raiseError(NotFound.block(request.blockHash))
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
    def hasBlock(blockHash: ByteString): F[Boolean]
    def getBlockSummary(blockHash: ByteString): F[Option[BlockSummary]]
    def getBlock(blockHash: ByteString): F[Option[Block]]

    /** Returns latest messages as seen currently by the node.
      * NOTE: In the future we will remove redundant messages. */
    def latestMessages: F[Set[Block.Justification]]

    /* Retrieve the DAG slice in topological order, inclusive */
    def dagTopoSort(startRank: Long, endRank: Long): Iterant[F, BlockSummary]
  }

  def apply[F[_]: Concurrent: Parallel: Log: Metrics](
      backend: GossipServiceServer.Backend[F],
      synchronizer: Synchronizer[F],
      downloadManager: DownloadManager[F],
      genesisApprover: GenesisApprover[F],
      maxChunkSize: Int,
      maxParallelBlockDownloads: Int
  ): F[GossipServiceServer[F]] =
    for {
      blockDownloadSemaphore <- Semaphore[F](maxParallelBlockDownloads.toLong)
    } yield new GossipServiceServer(
      backend,
      synchronizer,
      downloadManager,
      genesisApprover,
      maxChunkSize,
      blockDownloadSemaphore
    )
}
