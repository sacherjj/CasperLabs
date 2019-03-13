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

  /** Create Monix specific instance from the internal interface,
	  * to be used as the "server side", i.e. to return data to another peer. */
  def fromGossipService[F[_]: Sync: Effect: Taskable](
      service: GossipService[F]
  ): F[GossipingGrpcMonix.GossipService] = Sync[F].delay {
    new GossipingGrpcMonix.GossipService {

      /** Handle notification about some new blocks on the caller. */
      def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] =
        Taskable[F].toTask(service.newBlocks(request))

      def streamAncestorBlockSummaries(
          request: StreamAncestorBlockSummariesRequest
      ): Observable[BlockSummary] =
        toObservable(service.streamAncestorBlockSummaries(request))

      def streamDagTipBlockSummaries(
          request: StreamDagTipBlockSummariesRequest
      ): Observable[BlockSummary] =
        toObservable(service.streamDagTipBlockSummaries(request))

      def batchGetBlockSummaries(
          request: BatchGetBlockSummariesRequest
      ): Task[BatchGetBlockSummariesResponse] =
        Taskable[F].toTask(service.batchGetBlockSummaries(request))

      def getBlockChunked(request: GetBlockChunkedRequest): Observable[Chunk] =
        toObservable(service.getBlockChunked(request))

      def toObservable[A](it: Iterant[F, A]) =
        Observable.fromReactivePublisher(it.toReactivePublisher)
    }
  }

  /** Create the internal interface from the Monix specific instance,
    * to be used as the "client side", i.e. to request data from another peer. */
  def toGossipService[F[_]: Sync: TaskLift: Async](
      stub: GossipingGrpcMonix.GossipService
  )(implicit scheduler: Scheduler): F[GossipService[F]] = Sync[F].delay {
    new GossipService[F] {

      /** Notify the callee about new blocks. */
      def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
        TaskLift[F].taskLift(stub.newBlocks(request))

      def streamAncestorBlockSummaries(
          request: StreamAncestorBlockSummariesRequest
      ): Iterant[F, BlockSummary] =
        toIterant(stub.streamAncestorBlockSummaries(request))

      def streamDagTipBlockSummaries(
          request: StreamDagTipBlockSummariesRequest
      ): Iterant[F, BlockSummary] =
        toIterant(stub.streamDagTipBlockSummaries(request))

      def batchGetBlockSummaries(
          request: BatchGetBlockSummariesRequest
      ): F[BatchGetBlockSummariesResponse] =
        TaskLift[F].taskLift(stub.batchGetBlockSummaries(request))

      def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
        toIterant(stub.getBlockChunked(request))

      def toIterant[A](obs: Observable[A]): Iterant[F, A] =
        // TODO: Test that if we stop iterating the underlying observable gets canceled.
        Iterant.fromReactivePublisher[F, A](obs.toReactivePublisher)
    }
  }
}
