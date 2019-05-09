package io.casperlabs

import cats.Monad
import cats.{~>, Applicative, Monad, Parallel}
import cats.effect.{Concurrent, ConcurrentEffect, Fiber, Resource}
import cats.data.EitherT
import cats.syntax.applicative._
import cats.syntax.either._
import cats.effect.Resource
import cats.temp.par.Par
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.eitherT._
import io.casperlabs.comm.CommError
import monix.eval.{Task, TaskLike}
import monix.eval.instances.CatsParallelForTask
import monix.execution.Scheduler

package node {
  case class CommErrorException(error: CommError) extends Exception(error.toString)
}

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

  val catsParForEffect: Par[Effect] = Par.fromParallel(catsParallelForEffect)

  // We could try figuring this out for a type as follows and then we wouldn't have to use `raiseError`:
  // type EffectPar[A] = EitherT[Task.Par, CommError, A]
  val catsParallelForEffect = new Parallel[Effect, Task.Par] {

    override def applicative: Applicative[Task.Par] = CatsParallelForTask.applicative
    override def monad: Monad[Effect]               = Monad[Effect]

    override val sequential: Task.Par ~> Effect = new (Task.Par ~> Effect) {
      def apply[A](fa: Task.Par[A]): Effect[A] = {
        val task = Task.Par.unwrap(fa).map(_.asRight[CommError]).onErrorRecover {
          case CommErrorException(ce) => ce.asLeft[A]
        }
        EitherT(task)
      }
    }
    override val parallel: Effect ~> Task.Par = new (Effect ~> Task.Par) {
      def apply[A](fa: Effect[A]): Task.Par[A] = {
        val task = fa.value.flatMap {
          case Left(ce) => Task.raiseError(new CommErrorException(ce))
          case Right(a) => Task.pure(a)
        }
        Task.Par.apply(task)
      }
    }
  }

  def catsConcurrentEffectForEffect(implicit scheduler: Scheduler) =
    new ConcurrentEffect[Effect] {
      val C = implicitly[Concurrent[Effect]]
      val E = implicitly[cats.effect.Effect[Task]]
      val F = implicitly[ConcurrentEffect[Task]]

      // Members declared in cats.Applicative
      def pure[A](x: A): Effect[A] =
        C.pure[A](x)

      // Members declared in cats.ApplicativeError
      def handleErrorWith[A](fa: Effect[A])(f: Throwable => Effect[A]): Effect[A] =
        C.handleErrorWith[A](fa)(f)

      def raiseError[A](e: Throwable): Effect[A] =
        C.raiseError[A](e)

      // Members declared in cats.effect.Async
      def async[A](k: (Either[Throwable, A] => Unit) => Unit): Effect[A] =
        C.async[A](k)

      def asyncF[A](k: (Either[Throwable, A] => Unit) => Effect[Unit]): Effect[A] =
        C.asyncF[A](k)

      // Members declared in cats.effect.Bracket
      def bracketCase[A, B](acquire: Effect[A])(
          use: A => Effect[B]
      )(release: (A, cats.effect.ExitCase[Throwable]) => Effect[Unit]): Effect[B] =
        C.bracketCase[A, B](acquire)(use)(release)

      // Members declared in cats.effect.Concurrent
      def racePair[A, B](
          fa: Effect[A],
          fb: Effect[B]
      ): Effect[Either[(A, Fiber[Effect, B]), (Fiber[Effect, A], B)]] =
        C.racePair[A, B](fa, fb)

      def start[A](fa: Effect[A]): Effect[Fiber[Effect, A]] =
        C.start[A](fa)

      // Members declared in cats.effect.ConcurrentEffect
      def runCancelable[A](fa: Effect[A])(
          cb: Either[Throwable, A] => cats.effect.IO[Unit]
      ): cats.effect.SyncIO[cats.effect.CancelToken[Effect]] =
        F.runCancelable(fa.value) {
          case Left(ex)        => cb(Left(ex))
          case Right(Left(ce)) => cb(Left(CommErrorException(ce)))
          case Right(Right(x)) => cb(Right(x))
        } map { x =>
          EitherT {
            x.map(_.asRight[CommError]).onErrorRecover {
              case CommErrorException(ce) => ce.asLeft[Unit]
            }
          }
        }

      // Members declared in cats.effect.Effect
      def runAsync[A](fa: Effect[A])(
          cb: Either[Throwable, A] => cats.effect.IO[Unit]
      ): cats.effect.SyncIO[Unit] =
        E.runAsync(fa.value) {
          case Left(ex)        => cb(Left(ex))
          case Right(Left(ce)) => cb(Left(CommErrorException(ce)))
          case Right(Right(x)) => cb(Right(x))
        }

      // Members declared in cats.FlatMap
      def flatMap[A, B](fa: Effect[A])(f: A => Effect[B]): Effect[B] =
        C.flatMap[A, B](fa)(f)

      def tailRecM[A, B](a: A)(f: A => Effect[Either[A, B]]): Effect[B] =
        C.tailRecM[A, B](a)(f)

      // Members declared in cats.effect.Sync
      def suspend[A](thunk: => Effect[A]): Effect[A] =
        C.suspend[A](thunk)

    }
}
