package io.casperlabs.shared

import cats.Monad
import cats.effect.Sync
import cats.mtl.MonadState
import monix.execution.atomic.Atomic

class AtomicMonadState[F[_]: Sync, S](state: Atomic[S])(implicit val monad: Monad[F])
    extends MonadState[F, S] {
  def get: F[S]                   = Sync[F].delay(state.get)
  def set(s: S): F[Unit]          = Sync[F].delay(state.set(s))
  def inspect[A](f: S => A): F[A] = Sync[F].delay(f(state.get))
  def modify(f: S => S): F[Unit]  = Sync[F].delay(state.transform(f))
}
