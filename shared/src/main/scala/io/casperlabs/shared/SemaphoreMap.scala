package io.casperlabs.shared

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._

/** Keep track of semaphores when we want to limit to number of outstanding operations per key. */
class SemaphoreMap[F[_]: Concurrent, K](capacity: Int, ref: Ref[F, Map[K, Semaphore[F]]]) {
  def getOrAdd(key: K): F[Semaphore[F]] =
    ref.get.map(_.get(key)).flatMap {
      case Some(semaphore) =>
        semaphore.pure[F]
      case None =>
        for {
          s0 <- Semaphore[F](capacity.toLong)
          s1 <- ref.modify { ss =>
                 ss.get(key) map { s1 =>
                   ss -> s1
                 } getOrElse {
                   ss.updated(key, s0) -> s0
                 }
               }
        } yield s1
    }

  def withPermit[T](key: K)(block: F[T]) =
    getOrAdd(key).flatMap { semaphore =>
      semaphore.withPermit {
        block
      }
    }
}

object SemaphoreMap {
  def apply[F[_]: Concurrent, K](capacity: Int) =
    for {
      ref <- Ref[F].of(Map.empty[K, Semaphore[F]])
    } yield new SemaphoreMap[F, K](capacity, ref)
}
