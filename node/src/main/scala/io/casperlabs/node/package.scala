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
import io.casperlabs.comm.CommError
import monix.eval.instances.CatsParallelForTask
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler

package object node {

  /** Final Effect + helper methods */
  type Effect[A] = Task[A]

  implicit class TaskEffectOps[A](t: Task[A]) {
    def toEffect: Effect[A] = t
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

  implicit def eitherTApplicativeAsk[A](
      implicit ev: ApplicativeAsk[Task, A]
  ): ApplicativeAsk[Effect, A] =
    new DefaultApplicativeAsk[Effect, A] {
      val applicative: Applicative[Effect] = Applicative[Effect]
      def ask: Effect[A]                   = ev.ask.toEffect
    }

  implicit class ResourceTaskEffectOps[A](r: Resource[Task, A]) {
    def toEffect: Resource[Effect, A] = Resource {
      r.allocated.map {
        case (x, releaseTask) => (x, releaseTask.toEffect)
      }.toEffect
    }
  }
}
