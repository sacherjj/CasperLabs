package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.shared.Compression
import io.casperlabs.comm.ServiceError.NotFound
import monix.tail.Iterant

/** Server side implementation talking to the rest of the node such as casper, storage, download manager. */
class GossipServiceServer[F[_]: Sync](
    getBlockSummary: ByteString => F[Option[BlockSummary]],
    getBlock: ByteString => F[Option[Block]],
    maxChunkSize: Int
) extends GossipService[F] {
  import GossipServiceServer.chunkIt

  def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] = ???

  def streamAncestorBlockSummaries(
      request: StreamAncestorBlockSummariesRequest
  ): Iterant[F, BlockSummary] = ???

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
