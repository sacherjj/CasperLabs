package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import monix.eval.Task
import monix.tail.Iterant

trait NoOpsGossipService[F[_]] extends GossipService[F] {
  override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse]    = ???
  override def newDeploys(request: NewDeploysRequest): F[NewDeploysResponse] = ???
  override def streamAncestorBlockSummaries(
      request: StreamAncestorBlockSummariesRequest
  ): Iterant[F, BlockSummary] = ???
  override def streamLatestMessages(
      request: StreamLatestMessagesRequest
  ): Iterant[F, Block.Justification] = ???
  override def streamBlockSummaries(
      request: StreamBlockSummariesRequest
  ): Iterant[F, BlockSummary] = ???
  override def streamDeploySummaries(
      request: StreamDeploySummariesRequest
  ): Iterant[F, DeploySummary]                                                               = ???
  override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk]           = ???
  override def streamDeploysChunked(request: StreamDeploysChunkedRequest): Iterant[F, Chunk] = ???
  override def addApproval(request: AddApprovalRequest): F[Unit]                             = ???
  override def getGenesisCandidate(request: GetGenesisCandidateRequest): F[GenesisCandidate] = ???
  override def streamDagSliceBlockSummaries(
      request: StreamDagSliceBlockSummariesRequest
  ): Iterant[F, BlockSummary] = ???
}
