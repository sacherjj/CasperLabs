package io.casperlabs.casper

import cats.Monad
import cats.syntax.functor._
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.shared.LogStub
import monix.execution.Scheduler
import monix.eval.Task
import org.scalatest.{Assertion, Assertions, Matchers}
import org.scalactic.source

object scalatestcontrib extends Matchers with Assertions {
  implicit class AnyShouldF[F[_]: Monad, T](leftSideValue: F[T])(implicit pos: source.Position) {
    def shouldBeF(value: T): F[Assertion] =
      leftSideValue.map(_ shouldBe value)
  }

  def effectTest[T](f: Task[T])(implicit scheduler: Scheduler): T =
    f.unsafeRunSync(scheduler)
}
