package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.shared.StreamT

/** Intra-node gossiping based on the gRPC service definition. */
trait GossipService[F[_]] {
  def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse]

  def streamAncestorBlockSummaries(
      request: StreamAncestorBlockSummariesRequest
  ): StreamT[F, BlockSummary]

  def streamDagTipBlockSummaries(
      request: StreamDagTipBlockSummariesRequest
  ): StreamT[F, BlockSummary]

  def batchGetBlockSummaries(
      request: BatchGetBlockSummariesRequest
  ): F[BatchGetBlockSummariesResponse]

  /** Get one full block in chunks, optionally compressed, so that it can be transferred over the wire. */
  def getBlockChunked(request: GetBlockChunkedRequest): F[StreamT[F, Chunk]]
}
