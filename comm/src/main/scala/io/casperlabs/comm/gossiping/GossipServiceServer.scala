package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.{Block, BlockSummary, GenesisCandidate}
import io.casperlabs.shared.{Compression, Log, LogSource}
import io.casperlabs.comm.ServiceError
import ServiceError.NotFound
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import monix.tail.Iterant

import scala.collection.immutable.Queue
import scala.util.control.NonFatal

/** Server side implementation talking to the rest of the node such as casper, storage, download manager. */
class GossipServiceServer[F[_]: Concurrent: Par: Log](
    backend: GossipServiceServer.Backend[F],
    synchronizer: Synchronizer[F],
    downloadManager: DownloadManager[F],
    consensus: GossipServiceServer.Consensus[F],
    genesisApprover: GenesisApprover[F],
    maxChunkSize: Int,
    blockDownloadSemaphore: Semaphore[F]
) extends GossipService[F] {
  import GossipServiceServer._

  override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
    // Collect the blocks which we don't have yet;
    // reply about those that we are going to download and relay them,
    // then asynchronously sync the DAG, schedule the downloads,
    // and finally notify the consensus engine.
    request.blockHashes.distinct.toList
      .filterA { blockHash =>
        backend.hasBlock(blockHash).map(!_)
      }
      .flatMap { newBlockHashes =>
        if (newBlockHashes.isEmpty) {
          Applicative[F].pure(NewBlocksResponse(isNew = false))
        } else {
          Concurrent[F]
            .start(sync(request.getSender, newBlockHashes.toSet))
            .as(NewBlocksResponse(isNew = true))
        }
      }

  /** Synchronize and download any missing blocks to get to the new ones. */
  private def sync(source: Node, newBlockHashes: Set[ByteString]): F[Unit] = {

    //TODO: Define handling strategies
    def handleSyncError(syncError: SyncError): F[Unit] = {
      val prefix = s"Failed to sync DAG, source: ${source.show}."
      syncError match {
        case SyncError.TooDeep(summaries, limit) =>
          Log[F].warn(
            s"$prefix Returned DAG is too deep, limit: $limit, exceeded hashes: ${summaries
              .map(b16)}"
          )
        case SyncError.TooWide(maxBranchingFactor, maxTotal, total) =>
          Log[F].warn(
            s"$prefix Returned dag seems to be exponentially wide, max branching factor: $maxBranchingFactor, max total summaries: $maxTotal, total returned: $total"
          )
        case SyncError.Unreachable(summary, requestedDepth) =>
          Log[F].warn(
            s"$prefix During streaming source returned unreachable block summary: ${b16(summary)}, requested depth: $requestedDepth"
          )
        case SyncError.ValidationError(summary, e) =>
          Log[F].warn(
            s"$prefix Failed to validated the block summary: ${b16(summary)}, reason: $e"
          )
        case SyncError.MissingDependencies(hashes) =>
          Log[F].warn(
            s"$prefix Missing dependencies: ${hashes.map(b16)}"
          )
        case SyncError.Cycle(summary) =>
          Log[F].warn(s"$prefix Detected cycle: ${b16(summary)}")
      }
    }

    val trySync = for {
      _ <- Log[F].info(s"Notified about ${newBlockHashes.size} new blocks by ${source.show}.")
      dagOrError <- synchronizer.syncDag(
                     source = source,
                     targetBlockHashes = newBlockHashes
                   )
      _ <- dagOrError.fold(
            syncError => handleSyncError(syncError), { dag =>
              for {
                _ <- consensus.onPending(dag)
                watches <- dag.traverse { summary =>
                            downloadManager.scheduleDownload(
                              summary,
                              source = source,
                              relay = newBlockHashes(summary.blockHash)
                            )
                          }
                _ <- (dag zip watches).parTraverse {
                      case (summary, watch) =>
                        // TODO: Called from here the consensus engine can be notified multiple times.
                        // The pending and final notifications should most likely be moved
                        // to the DownloadManager. We'll see this clearer when we integrate
                        // the new gossiping machinery with Casper and the Block Strategy.
                        watch *> consensus.onDownloaded(summary.blockHash)
                    }
                _ <- Log[F].info(s"Synced ${dag.size} blocks with ${source.show}.")
              } yield ()
            }
          )
    } yield ()

    trySync.onError {
      case NonFatal(ex) =>
        Log[F].error(s"Could not sync new blocks with ${source.show}.", ex)
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
                      summary.getHeader.parentHashes ++
                        summary.getHeader.justifications.map(_.latestBlockHash)

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

  override def streamDagTipBlockSummaries(
      request: StreamDagTipBlockSummariesRequest
  ): Iterant[F, BlockSummary] =
    Iterant.liftF(consensus.listTips).flatMap(Iterant.fromSeq(_))

  override def streamBlockSummaries(
      request: StreamBlockSummariesRequest
  ): Iterant[F, BlockSummary] =
    Iterant[F]
      .fromSeq(request.blockHashes)
      .mapEval(backend.getBlockSummary(_))
      .flatMap(Iterant.fromIterable(_))

  override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
    Iterant.resource(blockDownloadSemaphore.acquire)(_ => blockDownloadSemaphore.release) flatMap {
      _ =>
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

  override def addApproval(request: AddApprovalRequest): F[Empty] =
    rethrow(genesisApprover.addApproval(request.blockHash, request.getApproval)) *> Empty().pure[F]

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

  def b16(summary: BlockSummary): String = Base16.encode(summary.blockHash.toByteArray)

  def b16(hash: ByteString): String = Base16.encode(hash.toByteArray)

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

  /** Interface to local storage. */
  trait Backend[F[_]] {
    def hasBlock(blockHash: ByteString): F[Boolean]
    def getBlockSummary(blockHash: ByteString): F[Option[BlockSummary]]
    def getBlock(blockHash: ByteString): F[Option[Block]]
  }

  /** Interface to the consensus engine. These could be exposed as Observables. */
  trait Consensus[F[_]] {

    /** Notify about new blocks we were told about but haven't acquired yet.
      * Pass them in topological order. */
    def onPending(dag: Vector[BlockSummary]): F[Unit]

    /** Notify about a new block we downloaded, verified and stored. */
    def onDownloaded(blockHash: ByteString): F[Unit]

    /** Retrieve the current tips of the DAG, the ones we'd build a block on. */
    def listTips: F[Seq[BlockSummary]]
  }

  def apply[F[_]: Concurrent: Par: Log](
      backend: GossipServiceServer.Backend[F],
      synchronizer: Synchronizer[F],
      downloadManager: DownloadManager[F],
      consensus: Consensus[F],
      genesisApprover: GenesisApprover[F],
      maxChunkSize: Int,
      maxParallelBlockDownloads: Int
  ): F[GossipServiceServer[F]] =
    Semaphore[F](maxParallelBlockDownloads.toLong) map { blockDownloadSemaphore =>
      new GossipServiceServer(
        backend,
        synchronizer,
        downloadManager,
        consensus,
        genesisApprover,
        maxChunkSize,
        blockDownloadSemaphore
      )
    }
}
