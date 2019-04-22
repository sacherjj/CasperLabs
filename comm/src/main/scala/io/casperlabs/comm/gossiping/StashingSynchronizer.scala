package io.casperlabs.comm.gossiping
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

class StashingSynchronizer[F[_]: Concurrent: Par](
    underlying: Synchronizer[F],
    stashedRequestsRef: Ref[F, Map[Node, (Deferred[F, Vector[BlockSummary]], Set[ByteString])]],
    transitionedRef: Ref[F, Boolean]
) extends Synchronizer[F] {

  override def syncDag(source: Node, targetBlockHashes: Set[ByteString]): F[Vector[BlockSummary]] =
    for {
      transitioned <- transitionedRef.get
      dag <- if (transitioned) {
              underlying.syncDag(source, targetBlockHashes)
            } else {
              for {
                maybeInitialDeferred <- Deferred[F, Vector[BlockSummary]]
                deferred <- stashedRequestsRef.modify { stashedRequests =>
                             val (d, hashes) =
                               stashedRequests.getOrElse(source, (maybeInitialDeferred, Set.empty))
                             (stashedRequests.updated(source, (d, hashes ++ targetBlockHashes)), d)
                           }
                res <- deferred.get
              } yield res
            }
    } yield dag

  private def run: F[Unit] =
    for {
      _               <- transitionedRef.set(true)
      stashedRequests <- stashedRequestsRef.get
      _ <- stashedRequests.toList.parTraverse {
            case (source, (deferred, hashes)) =>
              underlying.syncDag(source, hashes) >>= deferred.complete
          }
    } yield ()
}

object StashingSynchronizer {

  def wrap[F[_]: Concurrent: Par](
      underlying: Synchronizer[F],
      awaitApproved: F[Unit]
  ): F[Synchronizer[F]] =
    for {
      stashedRequestsRef <- Ref
                             .of[F, Map[
                               Node,
                               (Deferred[F, Vector[BlockSummary]], Set[ByteString])
                             ]](Map.empty)
      transitionedRef <- Ref.of[F, Boolean](false)
      s <- Sync[F].delay(
            new StashingSynchronizer[F](
              underlying,
              stashedRequestsRef,
              transitionedRef
            )
          )
      _ <- (awaitApproved >> s.run).start
    } yield s
}
