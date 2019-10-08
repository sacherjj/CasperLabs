package io.casperlabs

import cats.Monad
import cats.data.EitherT
import monix.eval.{Task, TaskLike}

package object node {

  implicit def eitherTTaskable[F[_]: Monad: TaskLike, E]: TaskLike[EitherT[F, E, ?]] =
    new TaskLike[EitherT[F, E, ?]] {
      case class ToTaskException(e: E) extends RuntimeException

      def apply[A](fa: EitherT[F, E, A]): Task[A] =
        TaskLike[F]
          .apply(fa.value)
          .flatMap {
            case Right(a) => Task.now(a)
            case Left(e)  => Task.raiseError[A](ToTaskException(e))
          }

    }
}
