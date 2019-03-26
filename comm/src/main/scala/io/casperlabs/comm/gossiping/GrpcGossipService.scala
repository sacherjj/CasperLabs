package io.casperlabs.comm.gossiping

import cats.effect._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.auth.Principal
import io.casperlabs.comm.grpc.ContextKeys
import io.casperlabs.comm.ServiceError.{NotFound, Unauthenticated}
import monix.eval.{Task, TaskLift, TaskLike}
import monix.reactive.Observable
import monix.tail.Iterant

/** Adapt the GossipService to Monix generated interfaces. */
object GrpcGossipService {
  import ObservableIterant.syntax._

  /** Create Monix specific instance from the internal interface,
	  * to be used as the "server side", i.e. to return data to another peer. */
  def fromGossipService[F[_]: Sync: TaskLike: ObservableIterant](
      service: GossipService[F]
  ): GossipingGrpcMonix.GossipService =
    new GossipingGrpcMonix.GossipService {

      /** Handle notification about some new blocks on the caller. */
      def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] =
        // Verify that the sender holds the same node identity as the public key
        // in the client SSL certificate. Alternatively we could drop the sender
        // altogether and use Kademlia to lookup the Node with that ID the first
        // time we see it.
        (request.sender, Option(ContextKeys.Principal.get)) match {
          case (None, _) =>
            Task.raiseError(Unauthenticated("Sender cannot be empty."))

          case (_, None) =>
            Task.raiseError(Unauthenticated("Cannot verify sender identity."))

          case (Some(sender), Some(Principal.Peer(id))) if sender.id != id =>
            Task.raiseError(Unauthenticated("Sender doesn't match public key."))

          case (Some(_), Some(Principal.Peer(_))) =>
            TaskLike[F].toTask(service.newBlocks(request))
        }

      def streamAncestorBlockSummaries(
          request: StreamAncestorBlockSummariesRequest
      ): Observable[BlockSummary] =
        service.streamAncestorBlockSummaries(request).toObservable

      def streamDagTipBlockSummaries(
          request: StreamDagTipBlockSummariesRequest
      ): Observable[BlockSummary] =
        service.streamDagTipBlockSummaries(request).toObservable

      def streamBlockSummaries(
          request: StreamBlockSummariesRequest
      ): Observable[BlockSummary] =
        service.streamBlockSummaries(request).toObservable

      def getBlockChunked(request: GetBlockChunkedRequest): Observable[Chunk] =
        service.getBlockChunked(request).toObservable
    }

  /** Create the internal interface from the Monix specific instance,
    * to be used as the "client side", i.e. to request data from another peer. */
  def toGossipService[F[_]: Sync: TaskLift: ObservableIterant](
      stub: GossipingGrpcMonix.GossipService
  ): GossipService[F] =
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

      def streamBlockSummaries(
          request: StreamBlockSummariesRequest
      ): Iterant[F, BlockSummary] =
        stub.streamBlockSummaries(request).toIterant

      def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
        stub.getBlockChunked(request).toIterant
    }
}
