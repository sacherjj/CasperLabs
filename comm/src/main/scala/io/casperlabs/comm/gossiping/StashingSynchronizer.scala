package io.casperlabs.comm.gossiping

import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import scala.concurrent.duration._

class StashingSynchronizer[F[_]: Concurrent: Par](
    underlying: Synchronizer[F],
    stashedRequestsRef: Ref[F, StashingSynchronizer.Stash[F]],
    transitionedRef: Ref[F, Boolean],
    semaphore: Semaphore[F]
) extends Synchronizer[F] {
  import StashingSynchronizer.SyncResult

  // The checking of the transitioned state and the adding to the stash has to be atomic.
  private def schedule(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[F[Either[Throwable, SyncResult]]] =
    semaphore.withPermit {
      transitionedRef.get.ifM(
        underlying.syncDag(source, targetBlockHashes).attempt.pure[F],
        for {
          maybeInitialDeferred <- Deferred[F, Either[Throwable, SyncResult]]
          deferred <- stashedRequestsRef.modify { stashedRequests =>
                       val (d, hashes) =
                         stashedRequests.getOrElse(source, (maybeInitialDeferred, Set.empty))
                       (stashedRequests.updated(source, (d, hashes ++ targetBlockHashes)), d)
                     }
        } yield deferred.get
      )
    }

  // Schedule a sync and await result.
  override def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[SyncResult] =
    for {
      attempt <- schedule(source, targetBlockHashes)
      res     <- attempt.rethrow
    } yield res

  override def downloaded(blockHash: ByteString) =
    underlying.downloaded(blockHash)

  private def run: F[Unit] =
    for {
      _               <- semaphore.withPermit(transitionedRef.set(true))
      stashedRequests <- stashedRequestsRef.get
      _ <- stashedRequests.toList.parTraverse {
            case (source, (deferred, hashes)) =>
              underlying.syncDag(source, hashes).attempt >>= deferred.complete
          }
    } yield ()
}

object StashingSynchronizer {

  type SyncResult  = Either[SyncError, Vector[BlockSummary]]
  type Stash[F[_]] = Map[Node, (Deferred[F, Either[Throwable, SyncResult]], Set[ByteString])]

  def wrap[F[_]: Concurrent: Par](
      underlying: Synchronizer[F],
      awaitApproved: F[Unit]
  ): F[Synchronizer[F]] =
    for {
      stashedRequestsRef <- Ref.of[F, Stash[F]](Map.empty)
      transitionedRef    <- Ref.of[F, Boolean](false)
      semaphore          <- Semaphore[F](1)
      s <- Sync[F].delay(
            new StashingSynchronizer[F](
              underlying,
              stashedRequestsRef,
              transitionedRef,
              semaphore
            )
          )
      _ <- (awaitApproved >> s.run).start
    } yield s
}
