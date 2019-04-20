package io.casperlabs.comm.gossiping
import cats.effect.{Concurrent, Sync}
import cats.effect.implicits._
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.StashingSynchronizer.Request
import io.casperlabs.shared.Cell

class StashingSynchronizer[F[_]: Concurrent: Par](
    underlying: Synchronizer[F],
    stashedRequestsRef: Ref[F, Map[Node, List[Request[F]]]],
    // Prevents concurrent scheduling during processing of kept requests in 'run'
    semaphoresCell: Cell[F, Map[Node, Semaphore[F]]],
    transitionedRef: Ref[F, Boolean]
) extends Synchronizer[F] {

  override def syncDag(source: Node, targetBlockHashes: Set[ByteString]): F[Vector[BlockSummary]] =
    for {
      _ <- semaphoresCell.flatModify { semaphores =>
            if (semaphores.contains(source)) {
              semaphores.pure[F]
            } else {
              Semaphore[F](1).map { semaphore =>
                semaphores.updated(source, semaphore)
              }
            }
          }
      semaphores   <- semaphoresCell.read
      transitioned <- transitionedRef.get
      dag <- if (transitioned) {
              semaphores(source).withPermit(underlying.syncDag(source, targetBlockHashes))
            } else {
              for {
                deferred <- Deferred[F, Vector[BlockSummary]]
                _ <- stashedRequestsRef.update { stashedRequests =>
                      val previous = stashedRequests.getOrElse(source, List.empty)
                      val merged   = Request(targetBlockHashes, deferred) :: previous
                      stashedRequests.updated(source, merged)
                    }
                res <- deferred.get
              } yield res
            }
    } yield dag

  def run: F[Unit] =
    for {
      _               <- transitionedRef.set(true)
      stashedRequests <- stashedRequestsRef.get
      _ <- stashedRequests.toList.parTraverse {
            case (source, requests) =>
              requests.reverse.traverse {
                case Request(hashes, deferred) =>
                  for {
                    semaphores <- semaphoresCell.read
                    _ <- semaphores(source).withPermit(
                          underlying.syncDag(source, hashes) >>= deferred.complete
                        )
                  } yield ()
              }.void
          }.void
    } yield ()
}

object StashingSynchronizer {
  final case class Request[F[_]](
      targetBlockHashes: Set[ByteString],
      deferred: Deferred[F, Vector[BlockSummary]]
  )

  def wrap[F[_]: Concurrent: Par](
      underlying: Synchronizer[F],
      awaitApproved: F[Unit]
  ): F[Synchronizer[F]] =
    for {
      stashedRequestsRef <- Ref
                             .of[F, Map[Node, List[Request[F]]]](Map.empty)
      semaphoresCell  <- Cell.mvarCell[F, Map[Node, Semaphore[F]]](Map.empty)
      transitionedRef <- Ref.of[F, Boolean](false)
      s <- Sync[F].delay(
            new StashingSynchronizer[F](
              underlying,
              stashedRequestsRef,
              semaphoresCell,
              transitionedRef
            )
          )
      _ <- (awaitApproved >> s.run).start
    } yield s
}
