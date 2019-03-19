package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.shared.Compression
import io.casperlabs.comm.ServiceError.NotFound
import monix.tail.Iterant
import scala.collection.mutable.PriorityQueue
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
    // Visit blocks in simple BFS order rather than try to establish topological sorting because BFS
    // is invariant in maximum depth, while topological sorting could depend on whether we traversed
    // backwards enough to re-join forks with different lengths of sub-paths.
    implicit val ord = breadthFirstOrdering
    // We return known hashes but not their parents.
    val knownHashes = request.knownBlockHashes.toSet

    def canGoDeeper(depth: Int) =
      depth < request.maxDepth || request.maxDepth == -1

    def loop(
        queue: PriorityQueue[(Int, ByteString)],
        visited: Set[ByteString]
    ): Iterant[F, BlockSummary] =
      if (queue.isEmpty)
        Iterant.empty
      else {
        queue.dequeue() match {
          case (_, blockHash) if visited(blockHash) =>
            loop(queue, visited)

          case (depth, blockHash) =>
            Iterant.liftF(getBlockSummary(blockHash)) flatMap {
              case None =>
                loop(queue, visited + blockHash)

              case Some(summary) =>
                if (canGoDeeper(depth) && !knownHashes(summary.blockHash)) {
                  val ancestors =
                    summary.getHeader.parentHashes ++
                      summary.getHeader.justifications.map(_.latestBlockHash)

                  queue.enqueue(ancestors.map(depth + 1 -> _): _*)
                }

                Iterant.pure(summary) ++ loop(queue, visited + blockHash)
            }
        }
      }

    Iterant.delay {
      PriorityQueue(request.targetBlockHashes.map(0 -> _): _*) -> Set.empty[ByteString]
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

  // Return `true` for the item that is "less important" then the other.
  val breadthFirstOrdering: Ordering[(Int, ByteString)] = Ordering.fromLessThan {
    case ((depth1, hash1), (depth2, hash2)) if depth1 == depth2 =>
      // Just want some stable order between blocks at the same depth.
      hash1.hashCode > hash2.hashCode
    case ((depth1, _), (depth2, _)) =>
      depth1 > depth2
  }
}
