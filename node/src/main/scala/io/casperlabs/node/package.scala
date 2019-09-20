package io.casperlabs

import cats.data.EitherT
import cats.effect.{Concurrent, ConcurrentEffect, Fiber, Resource}
import cats.mtl.{ApplicativeAsk, DefaultApplicativeAsk}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.temp.par.Par
import cats.{~>, Applicative, Monad, Parallel}
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.eitherT._
import monix.eval.instances.CatsParallelForTask
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler

package object node {

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

  implicit def eitherTApplicativeAsk[A](
      implicit ev: ApplicativeAsk[Task, A]
  ): ApplicativeAsk[Task, A] =
    new DefaultApplicativeAsk[Task, A] {
      val applicative: Applicative[Task] = Applicative[Task]
      def ask: Task[A]                   = ev.ask
    }

  implicit class ResourceTaskEffectOps[A](r: Resource[Task, A]) {
    def toEffect: Resource[Task, A] = Resource {
      r.allocated.map {
        case (x, releaseTask) => (x, releaseTask)
      }
    }
  }
}
