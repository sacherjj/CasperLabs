package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.shared.{Compression, Log, LogSource}
import io.casperlabs.comm.ServiceError.NotFound
import io.casperlabs.comm.discovery.Node
import monix.tail.Iterant
import scala.collection.immutable.Queue
import scala.util.control.NonFatal

/** Server side implementation talking to the rest of the node such as casper, storage, download manager. */
class GossipServiceServer[F[_]: Concurrent: Par: Log](
    backend: GossipServiceServer.Backend[F],
    synchronizer: Synchronizer[F],
    downloadManager: DownloadManager[F],
    consensus: GossipServiceServer.Consensus[F],
    maxChunkSize: Int,
    blockDownloadSemaphore: Semaphore[F]
) extends GossipService[F] {
  import GossipServiceServer._

  def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
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
    val peer = s"${Base16.encode(source.id.toByteArray)}@${source.host}"

    val trySync = for {
      _ <- Log[F].info(s"Notified about ${newBlockHashes.size} new blocks by ${peer}.")
      dag <- synchronizer.syncDag(
              source = source,
              targetBlockHashes = newBlockHashes
            )
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
      _ <- Log[F].info(s"Synced ${dag.size} blocks with ${peer}.")
    } yield ()

    trySync.onError {
      case NonFatal(ex) =>
        Log[F].error(s"Could not sync new blocks with ${peer}.", ex)
    }
  }

  def streamAncestorBlockSummaries(
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

  def streamDagTipBlockSummaries(
      request: StreamDagTipBlockSummariesRequest
  ): Iterant[F, BlockSummary] = ???

  def streamBlockSummaries(
      request: StreamBlockSummariesRequest
  ): Iterant[F, BlockSummary] =
    Iterant[F]
      .fromSeq(request.blockHashes)
      .mapEval(backend.getBlockSummary(_))
      .flatMap(Iterant.fromIterable(_))

  def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
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

  def effectiveChunkSize(chunkSize: Int): Int =
    if (0 < chunkSize && chunkSize < maxChunkSize) chunkSize
    else maxChunkSize
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
  }

  def apply[F[_]: Concurrent: Par: Log](
      backend: GossipServiceServer.Backend[F],
      synchronizer: Synchronizer[F],
      downloadManager: DownloadManager[F],
      consensus: Consensus[F],
      maxChunkSize: Int,
      maxParallelBlockDownloads: Int
  ): F[GossipServiceServer[F]] =
    Semaphore[F](maxParallelBlockDownloads.toLong) map { blockDownloadSemaphore =>
      new GossipServiceServer(
        backend,
        synchronizer,
        downloadManager,
        consensus,
        maxChunkSize,
        blockDownloadSemaphore
      )
    }
}
