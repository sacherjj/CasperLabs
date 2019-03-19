package io.casperlabs.catscontrib

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.mtl.MonadState
import cats.syntax.functor._
import cats.syntax.flatMap._
import com.olegpy.meow.hierarchy.deriveMonadState
import com.olegpy.meow.optics.MkLensToType
import com.olegpy.meow.effects._
import shapeless.{MkFieldLens, Witness}

import scala.language.higherKinds

object MonadStateOps {
  implicit class MonadStateOps[F[_], S](state: MonadState[F, S]) {
    def >>[A](k: Witness)(implicit mkLens: MkFieldLens.Aux[S, k.T, A]): MonadState[F, A] = {
      implicit val mkLens1 = new MkLensToType[S, A](mkLens())
      implicit val parent  = state

      deriveMonadState[F, S, A]
    }
  }

  class PartiallyAppliedUse[F[_], A](init: A) {
    def apply[B](f: MonadState[F, A] => B)(implicit sync: Sync[F]): F[B] =
      Ref[F].of(init).map(_.runState(f))
  }

  implicit class StateByRefOps[A](init: A) {
    def useStateByRef[F[_]] =
      new PartiallyAppliedUse[F, A](init)
  }
}
