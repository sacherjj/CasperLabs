package io.casperlabs.comm.gossiping

import cats.effect._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.catscontrib.Taskable
import monix.eval.{Task, TaskLift}
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.tail.Iterant

/** Adapt the GossipService to Monix generated interfaces. */
object GrpcGossipService {
  import ObservableIterant.syntax._

  /** Create Monix specific instance from the internal interface,
	  * to be used as the "server side", i.e. to return data to another peer. */
  def fromGossipService[F[_]: Sync: Taskable: ObservableIterant](
      service: GossipService[F]
  ): F[GossipingGrpcMonix.GossipService] = Sync[F].delay {
    new GossipingGrpcMonix.GossipService {

      /** Handle notification about some new blocks on the caller. */
      def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] =
        Taskable[F].toTask(service.newBlocks(request))

      def streamAncestorBlockSummaries(
          request: StreamAncestorBlockSummariesRequest
      ): Observable[BlockSummary] =
        service.streamAncestorBlockSummaries(request).toObservable

      def streamDagTipBlockSummaries(
          request: StreamDagTipBlockSummariesRequest
      ): Observable[BlockSummary] =
        service.streamDagTipBlockSummaries(request).toObservable

      def batchGetBlockSummaries(
          request: BatchGetBlockSummariesRequest
      ): Task[BatchGetBlockSummariesResponse] =
        Taskable[F].toTask(service.batchGetBlockSummaries(request))

      def getBlockChunked(request: GetBlockChunkedRequest): Observable[Chunk] =
        service.getBlockChunked(request).toObservable
    }
  }

  /** Create the internal interface from the Monix specific instance,
    * to be used as the "client side", i.e. to request data from another peer. */
  def toGossipService[F[_]: Sync: TaskLift: ObservableIterant](
      stub: GossipingGrpcMonix.GossipService
  ): F[GossipService[F]] = Sync[F].delay {
    new GossipService[F] {

      /** Notify the callee about new blocks. */
      def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
        stub.newBlocks(request).to[F]

      def streamAncestorBlockSummaries(
          request: StreamAncestorBlockSummariesRequest
      ): Iterant[F, BlockSummary] =
        stub.streamAncestorBlockSummaries(request).toIterant

      def streamDagTipBlockSummaries(
          request: StreamDagTipBlockSummariesRequest
      ): Iterant[F, BlockSummary] =
        stub.streamDagTipBlockSummaries(request).toIterant

      def batchGetBlockSummaries(
          request: BatchGetBlockSummariesRequest
      ): F[BatchGetBlockSummariesResponse] =
        stub.batchGetBlockSummaries(request).to[F]

      def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
        stub.getBlockChunked(request).toIterant
    }
  }
}
