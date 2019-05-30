package io.casperlabs.comm.gossiping

import cats.effect.Timer
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import scala.concurrent.duration._

class StashingSynchronizer[F[_]: Concurrent: Par: Timer](
    underlying: Synchronizer[F],
    stashedRequestsRef: Ref[F, Map[
      Node,
      (Deferred[F, Either[Throwable, Either[SyncError, Vector[BlockSummary]]]], Set[ByteString])
    ]],
    transitionedRef: Ref[F, Boolean]
) extends Synchronizer[F] {

  override def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Either[SyncError, Vector[BlockSummary]]] =
    for {
      transitioned <- transitionedRef.get
      dag <- if (transitioned) {
              underlying.syncDag(source, targetBlockHashes)
            } else {
              for {
                _ <- Timer[F].sleep(50.millis)
                maybeInitialDeferred <- Deferred[F, Either[Throwable, Either[SyncError, Vector[
                                         BlockSummary
                                       ]]]]
                deferred <- stashedRequestsRef.modify { stashedRequests =>
                             val (d, hashes) =
                               stashedRequests.getOrElse(source, (maybeInitialDeferred, Set.empty))
                             (stashedRequests.updated(source, (d, hashes ++ targetBlockHashes)), d)
                           }
                either <- deferred.get
                res <- either.fold(
                        Sync[F].raiseError[Either[SyncError, Vector[BlockSummary]]],
                        Sync[F].pure
                      )
              } yield res
            }
    } yield dag

  private def run: F[Unit] =
    for {
      _               <- transitionedRef.set(true)
      stashedRequests <- stashedRequestsRef.get
      _ <- stashedRequests.toList.parTraverse {
            case (source, (deferred, hashes)) =>
              underlying.syncDag(source, hashes).attempt >>= deferred.complete
          }
    } yield ()
}

object StashingSynchronizer {

  def wrap[F[_]: Concurrent: Par: Timer](
      underlying: Synchronizer[F],
      awaitApproved: F[Unit]
  ): F[Synchronizer[F]] =
    for {
      stashedRequestsRef <- Ref
                             .of[F, Map[
                               Node,
                               (
                                   Deferred[
                                     F,
                                     Either[Throwable, Either[SyncError, Vector[BlockSummary]]]
                                   ],
                                   Set[ByteString]
                               )
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
