package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.{Block, BlockSummary, GenesisCandidate}
import monix.tail.Iterant

/** Intra-node gossiping based on the gRPC service definition in gossiping.proto. */
trait GossipService[F[_]] {
  def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse]

  def streamAncestorBlockSummaries(
      request: StreamAncestorBlockSummariesRequest
  ): Iterant[F, BlockSummary]

  def streamLatestMessages(
      request: StreamLatestMessagesRequest
  ): Iterant[F, Block.Justification]

  def streamBlockSummaries(
      request: StreamBlockSummariesRequest
  ): Iterant[F, BlockSummary]

  /** Get a full block in chunks, optionally compressed, so that it can be transferred over the wire. */
  def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk]

  def getGenesisCandidate(request: GetGenesisCandidateRequest): F[GenesisCandidate]

  def addApproval(request: AddApprovalRequest): F[Unit]

  def streamDagSliceBlockSummaries(
      request: StreamDagSliceBlockSummariesRequest
  ): Iterant[F, BlockSummary]
}

object GossipService {
  type Connector[F[_]] = io.casperlabs.comm.discovery.Node => F[GossipService[F]]
}
