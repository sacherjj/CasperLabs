package io.casperlabs.comm.gossiping

import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.casperlabs.casper.consensus.{Block, BlockSummary, GenesisCandidate}
import io.casperlabs.comm.ServiceError.{DeadlineExceeded, Unauthenticated}
import io.casperlabs.comm.auth.Principal
import io.casperlabs.comm.discovery.{Node, NodeIdentifier}
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.comm.grpc.ContextKeys
import io.casperlabs.shared.ObservableOps._
import monix.eval.{Task, TaskLift, TaskLike}
import monix.reactive.Observable
import monix.tail.Iterant

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

/** Adapt the GossipService to Monix generated interfaces. */
object GrpcGossipService {
  import ObservableIterant.syntax._

  /** Create Monix specific instance from the internal interface,
    * to be used as the "server side", i.e. to return data to another peer. */
  def fromGossipService[F[_]: Sync: TaskLike: ObservableIterant](
      service: GossipService[F],
      rateLimiter: RateLimiter[F, ByteString],
      chainId: ByteString,
      blockChunkConsumerTimeout: FiniteDuration
  ): GossipingGrpcMonix.GossipService =
    new GossipingGrpcMonix.GossipService {

      /**
        * Verifies that the sender holds the same node identity as the public key in the client SSL certificate.
        */
      private def verifySender(
          maybeSender: Option[Node]
      ): Task[NodeIdentifier] =
        (maybeSender, Option(ContextKeys.Principal.get)) match {
          case (_, None) =>
            Task.raiseError[NodeIdentifier](
              Unauthenticated("Cannot verify sender identity.")
            )

          case (Some(sender), _) if sender.chainId != chainId =>
            Task.raiseError[NodeIdentifier](
              Unauthenticated(
                s"Sender doesn't match chain id, expected: ${hex(chainId)}, received: ${hex(sender.chainId)}"
              )
            )

          case (Some(sender), Some(Principal.Peer(id))) if sender.id != id =>
            Task.raiseError[NodeIdentifier](
              Unauthenticated("Sender doesn't match public key.")
            )

          case (_, Some(Principal.Peer(id))) =>
            Task.now(NodeIdentifier(id))
        }

      /** Handle notification about some new blocks on the caller. */
      def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] =
        verifySender(request.sender) >> TaskLike[F].apply(service.newBlocks(request))

      def streamAncestorBlockSummaries(
          request: StreamAncestorBlockSummariesRequest
      ): Observable[BlockSummary] =
        service.streamAncestorBlockSummaries(request).toObservable

      override def streamLatestMessages(
          request: StreamLatestMessagesRequest
      ): Observable[Block.Justification] =
        service.streamLatestMessages(request).toObservable

      def streamBlockSummaries(
          request: StreamBlockSummariesRequest
      ): Observable[BlockSummary] =
        service.streamBlockSummaries(request).toObservable

      def getBlockChunked(request: GetBlockChunkedRequest): Observable[Chunk] =
        Observable
          .fromTask(verifySender(maybeSender = None)) >>= { sender =>
          val stream = service
            .getBlockChunked(request)
            .toObservable
            .withConsumerTimeout(blockChunkConsumerTimeout)
            .onErrorRecoverWith {
              case ex: TimeoutException =>
                Observable.raiseError(DeadlineExceeded(ex.getMessage))
            }

          Observable
            .fromTask(
              TaskLike[F]
                .apply(rateLimiter.await(sender.asByteString, stream.pure[F]))
            )
            .flatten
        }

      def getGenesisCandidate(request: GetGenesisCandidateRequest): Task[GenesisCandidate] =
        TaskLike[F].apply(service.getGenesisCandidate(request))

      def addApproval(request: AddApprovalRequest): Task[Empty] =
        TaskLike[F].apply(service.addApproval(request).map(_ => Empty()))

      def streamDagSliceBlockSummaries(
          request: StreamDagSliceBlockSummariesRequest
      ): Observable[BlockSummary] = service.streamDagSliceBlockSummaries(request).toObservable
    }

  /** Create the internal interface from the Monix specific instance,
    * to be used as the "client side", i.e. to request data from another peer. */
  def toGossipService[F[_]: Sync: TaskLift: TaskLike: ObservableIterant](
      stub: GossipingGrpcMonix.GossipService,
      // Can inject a callback to close the faulty channel.
      onError: PartialFunction[Throwable, F[Unit]] = PartialFunction.empty,
      timeout: FiniteDuration = Duration.Zero
  ): GossipService[F] =
    new GossipService[F] {
      private def withErrorCallback[T](obs: Observable[T]): Iterant[F, T] = {
        val ot = if (timeout == Duration.Zero) obs else obs.timeoutOnSlowUpstream(timeout)
        ot.doOnErrorF(onError orElse { case _ => ().pure[F] }).toIterant
      }

      private def withErrorCallback[T](task: Task[T]): F[T] = {
        val tt = if (timeout == Duration.Zero) task else task.timeout(timeout)
        tt.to[F].onError(onError)
      }

      /** Notify the callee about new blocks. */
      def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
        withErrorCallback(stub.newBlocks(request))

      def streamAncestorBlockSummaries(
          request: StreamAncestorBlockSummariesRequest
      ): Iterant[F, BlockSummary] =
        withErrorCallback(stub.streamAncestorBlockSummaries(request))

      override def streamLatestMessages(
          request: StreamLatestMessagesRequest
      ): Iterant[F, Block.Justification] =
        withErrorCallback(stub.streamLatestMessages(request))

      def streamBlockSummaries(
          request: StreamBlockSummariesRequest
      ): Iterant[F, BlockSummary] =
        withErrorCallback(stub.streamBlockSummaries(request))

      def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
        withErrorCallback(stub.getBlockChunked(request))

      def getGenesisCandidate(request: GetGenesisCandidateRequest): F[GenesisCandidate] =
        withErrorCallback(stub.getGenesisCandidate(request))

      def addApproval(request: AddApprovalRequest): F[Unit] =
        withErrorCallback(stub.addApproval(request).map(_ => ()))

      def streamDagSliceBlockSummaries(
          request: StreamDagSliceBlockSummariesRequest
      ): Iterant[F, BlockSummary] = withErrorCallback(stub.streamDagSliceBlockSummaries(request))
    }
}
