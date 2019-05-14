package io.casperlabs.catscontrib

import cats.MonadError

// Mixed into the package object so the type alias is available under catscontrib.
trait MonadThrowableInstances {

  /** We can use this as a context bound when a method already uses `Monad`
    * but now we need to raise an error, but we don't want to go all the
    * way and use `Sync`. If the domain error extends `scala.util.control.NoStackTrace`
    * then it's easy to use `attempt` to return `F[Either[DomainError, A]]`
    * and `rethrow` to turn it back into `F[A]` if it's not something we want
    * to deal with.
    * It achieves the same as `F[_]: MonadError[?[_], Throwable]` with `F[_]: MonadThrowable */
  type MonadThrowable[F[_]] = MonadError[F, Throwable]
}

object MonadThrowable {
  // So that we can use `MonadThrowable[F].raiseError` rather than `MonadError[F, Throwable].raiseError`
  def apply[F[_]](implicit ev: MonadError[F, Throwable]) = ev
}
