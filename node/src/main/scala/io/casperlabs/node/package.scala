package io.casperlabs

import cats.data.EitherT
import cats.syntax.applicative._

import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.eitherT._
import io.casperlabs.comm.CommError

import monix.eval.Task

package object node {

  /** Final Effect + helper methods */
  type CommErrT[F[_], A] = EitherT[F, CommError, A]
  type Effect[A]         = CommErrT[Task, A]

  implicit class EitherEffectOps[A](e: Either[CommError, A]) {
    def toEffect: Effect[A] = EitherT[Task, CommError, A](e.pure[Task])
  }
  implicit class TaskEffectOps[A](t: Task[A]) {
    def toEffect: Effect[A] = t.liftM[CommErrT]
  }
}
