package io.casperlabs.catscontrib

import java.util.concurrent.TimeoutException

import cats.effect.{Concurrent, Timer}
import monix.eval.Task
import monix.execution.Scheduler
import cats.implicits._

import scala.concurrent.Await
import scala.concurrent.duration._

object TaskContrib {
  implicit class ConcurrentOps[F[_], A](val fa: F[A]) extends AnyVal {
    def nonCancelingTimeout(after: FiniteDuration)(implicit C: Concurrent[F], T: Timer[F]): F[A] =
      nonCancelingTimeoutTo(
        after,
        C.raiseError[A](new TimeoutException(s"Task timed-out after $after of inactivity"))
      )

    def nonCancelingTimeoutTo[B >: A](
        after: FiniteDuration,
        backup: F[B]
    )(implicit C: Concurrent[F], T: Timer[F]): F[B] =
      C.racePair(fa, T.sleep(after)).flatMap {
        case Left((a, _)) =>
          Concurrent[F].pure(a)
        case Right(_) =>
          backup
      }
  }

  implicit class TaskOps[A](task: Task[A]) {
    def unsafeRunSync(implicit scheduler: Scheduler): A =
      Await.result(task.runToFuture, Duration.Inf)

    def nonCancelingTimeout(after: FiniteDuration): Task[A] =
      nonCancelingTimeoutTo(
        after,
        Task.raiseError[A](new TimeoutException(s"Task timed-out after $after of inactivity"))
      )

    def nonCancelingTimeoutTo[B >: A](after: FiniteDuration, backup: Task[B]): Task[B] =
      Task.racePair(task, Task.unit.delayExecution(after)).flatMap {
        case Left((a, _)) =>
          Task.now(a)
        case Right(_) =>
          backup
      }

    // TODO should also push stacktrace to logs (not only console as it is doing right now)
    def attemptAndLog: Task[A] = task.attempt.flatMap {
      case Left(ex)      => Task.delay(ex.printStackTrace()) *> Task.raiseError[A](ex)
      case Right(result) => Task.pure(result)
    }

  }
}
