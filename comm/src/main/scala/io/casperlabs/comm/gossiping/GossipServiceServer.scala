package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.shared.Compression
import io.casperlabs.comm.ServiceError.NotFound
import monix.tail.Iterant
import scala.collection.immutable.Queue
import scala.math.Ordering

/** Server side implementation talking to the rest of the node such as casper, storage, download manager. */
class GossipServiceServer[F[_]: Sync](
    getBlockSummary: ByteString => F[Option[BlockSummary]],
    getBlock: ByteString => F[Option[Block]],
    maxChunkSize: Int
) extends GossipService[F] {
  import GossipServiceServer._

  def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] = ???

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
            Iterant.liftF(getBlockSummary(blockHash)) flatMap {
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
      .mapEval(getBlockSummary)
      .flatMap(Iterant.fromIterable(_))

  def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
    Iterant.liftF {
      getBlock(request.blockHash)
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
}
