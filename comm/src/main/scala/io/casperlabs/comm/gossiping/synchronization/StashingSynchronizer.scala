package io.casperlabs.comm.gossiping.synchronization

import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError

class StashingSynchronizer[F[_]: Concurrent](
    underlying: Synchronizer[F],
    stashedRequestsRef: Ref[F, StashingSynchronizer.Stash[F]],
    transitionedRef: Ref[F, Boolean],
    semaphore: Semaphore[F],
    isInDag: ByteString => F[Boolean]
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

  override def onDownloaded(blockHash: ByteString) =
    underlying.onDownloaded(blockHash)

  override def onFailed(blockHash: ByteString) =
    underlying.onFailed(blockHash)

  override def onScheduled(summary: BlockSummary, source: Node): F[Unit] =
    underlying.onScheduled(summary, source)

  private def run: F[Unit] =
    for {
      _               <- semaphore.withPermit(transitionedRef.set(true))
      stashedRequests <- stashedRequestsRef.modify(Map.empty -> _)
      // We could run in parallel but it likely racked up a lot of redundant requests,
      // from a lot of sources, so better take it easy.
      _ <- stashedRequests.toList.traverse {
            case (source, (deferred, hashes)) =>
              // The initial synchronization could have acquired all the ones we stashed.
              hashes.toList.filterA(isInDag(_).map(!_)).flatMap {
                case Nil =>
                  deferred.complete {
                    Vector.empty[BlockSummary].asRight[SyncError].asRight[Throwable]
                  }
                case targetBlockHashes =>
                  underlying.syncDag(source, targetBlockHashes.toSet).attempt >>= deferred.complete
              }
          }
    } yield ()
}

object StashingSynchronizer {

  type SyncResult  = Either[SyncError, Vector[BlockSummary]]
  type Stash[F[_]] = Map[Node, (Deferred[F, Either[Throwable, SyncResult]], Set[ByteString])]

  def wrap[F[_]: Concurrent](
      underlying: Synchronizer[F],
      await: F[Unit],
      isInDag: ByteString => F[Boolean]
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
              semaphore,
              isInDag
            )
          )
      _ <- (await >> s.run).start
    } yield s
}
