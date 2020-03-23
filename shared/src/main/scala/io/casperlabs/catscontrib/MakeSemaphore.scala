package io.casperlabs.catscontrib

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import simulacrum.typeclass

/** Requiring `Concurrent` just to be able to create a `Semaphore` is too powerful. */
@typeclass
trait MakeSemaphore[F[_]] {
  def apply(n: Long): F[Semaphore[F]]
}

object MakeSemaphore {
  implicit def fromConcurrent[F[_]: Concurrent]: MakeSemaphore[F] =
    new MakeSemaphore[F] {
      override def apply(n: Long): F[Semaphore[F]] =
        Semaphore(n)
    }

  def apply[F[_]](n: Long)(implicit ev: MakeSemaphore[F]) = ev(n)
}
