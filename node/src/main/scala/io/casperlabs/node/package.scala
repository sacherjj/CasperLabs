package io.casperlabs

import cats.Monad
import cats.data.EitherT
import cats.syntax.applicative._
import cats.effect.Resource
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.eitherT._
import io.casperlabs.comm.CommError
import monix.eval.{Task, TaskLike}

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

  implicit def eitherTTaskable[F[_]: Monad: TaskLike, E]: TaskLike[EitherT[F, E, ?]] =
    new TaskLike[EitherT[F, E, ?]] {
      case class ToTaskException(e: E) extends RuntimeException

      def toTask[A](fa: EitherT[F, E, A]): Task[A] =
        TaskLike[F]
          .toTask(fa.value)
          .flatMap {
            case Right(a) => Task.now(a)
            case Left(e)  => Task.raiseError[A](ToTaskException(e))
          }

    }

  implicit class ResourceTaskEffectOps[A](r: Resource[Task, A]) {
    def toEffect: Resource[Effect, A] = Resource {
      r.allocated.map {
        case (x, releaseTask) => (x, releaseTask.toEffect)
      }.toEffect
    }
  }
}
