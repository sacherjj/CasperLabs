package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.BlockSummary
import monix.tail.Iterant

/** Intra-node gossiping based on the gRPC service definition. */
trait GossipService[F[_]] {
  def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse]

  def streamAncestorBlockSummaries(
      request: StreamAncestorBlockSummariesRequest
  ): Iterant[F, BlockSummary]

  def streamDagTipBlockSummaries(
      request: StreamDagTipBlockSummariesRequest
  ): Iterant[F, BlockSummary]

  def streamBlockSummaries(
      request: StreamBlockSummariesRequest
  ): Iterant[F, BlockSummary]

  /** Get a full block in chunks, optionally compressed, so that it can be transferred over the wire. */
  def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk]
}
