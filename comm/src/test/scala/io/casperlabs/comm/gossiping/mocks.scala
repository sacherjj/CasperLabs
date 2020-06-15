package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import monix.eval.Task
import monix.tail.Iterant
import io.casperlabs.casper.consensus._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping.downloadmanager.{BlockDownloadManager, DeployDownloadManager}
import io.casperlabs.comm.gossiping.synchronization.Synchronizer, Synchronizer.SyncError
import io.casperlabs.comm.ServiceError

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

trait NoOpsBlockDownloadManager[F[_]] extends BlockDownloadManager[F] {
  override def scheduleDownload(
      summary: BlockSummary,
      source: Node,
      relay: Boolean
  ): F[WaitHandle[F]]                                                    = ???
  override def isScheduled(id: ByteString): F[Boolean]                   = ???
  override def addSource(id: ByteString, source: Node): F[WaitHandle[F]] = ???
  override def wasDownloaded(id: ByteString): F[Boolean]                 = ???
}

trait NoOpsDeployDownloadManager[F[_]] extends DeployDownloadManager[F] {
  override def scheduleDownload(
      summary: DeploySummary,
      source: Node,
      relay: Boolean
  ): F[WaitHandle[F]]                                                    = ???
  override def isScheduled(id: ByteString): F[Boolean]                   = ???
  override def addSource(id: ByteString, source: Node): F[WaitHandle[F]] = ???
  override def wasDownloaded(id: ByteString): F[Boolean]                 = ???
}

trait NoOpsGenesisApprover[F[_]] extends GenesisApprover[F] {
  override def getCandidate: F[Either[ServiceError, GenesisCandidate]] = ???
  override def addApproval(
      blockHash: ByteString,
      approval: Approval
  ): F[Either[ServiceError, Boolean]]       = ???
  override def awaitApproval: F[ByteString] = ???
}

trait NoOpsSynchronizer[F[_]] extends Synchronizer[F] {
  override def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Either[SyncError, Vector[BlockSummary]]]                          = ???
  override def onDownloaded(blockHash: ByteString): F[Unit]              = ???
  override def onFailed(blockHash: ByteString): F[Unit]                  = ???
  override def onScheduled(summary: BlockSummary, source: Node): F[Unit] = ???
}

trait NoOpsGossipServiceServerBackend[F[_]] extends GossipServiceServer.Backend[F] {
  override def getDeploySummary(deployHash: ByteString): F[Option[DeploySummary]] = ???
  override def getBlockSummary(blockHash: ByteString): F[Option[BlockSummary]]    = ???
  override def getBlock(blockHash: ByteString, deploysBodiesExcluded: Boolean): F[Option[Block]] =
    ???
  override def getDeploys(deployHashes: Set[ByteString]): Iterant[F, Deploy]         = ???
  override def latestMessages: F[Set[Block.Justification]]                           = ???
  override def dagTopoSort(startRank: Long, endRank: Long): Iterant[F, BlockSummary] = ???
}

trait NoOpsNodeDiscovery[F[_]] extends NodeDiscovery[F] {
  override def discover: F[Unit]                                  = ???
  override def lookup(id: NodeIdentifier): F[Option[Node]]        = ???
  override def recentlyAlivePeersAscendingDistance: F[List[Node]] = ???
  override def banTemp(node: Node): F[Unit]                       = ???
}
