package io.casperlabs.casper

import cats.Monad
import cats.syntax.functor._
import io.casperlabs.casper.helper.HashSetCasperTestNode.Effect
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import monix.execution.Scheduler
import org.scalatest.{Assertion, Assertions, Matchers}
import org.scalactic.source

object scalatestcontrib extends Matchers with Assertions {
  implicit class AnyShouldF[F[_]: Monad, T](leftSideValue: F[T])(implicit pos: source.Position) {
    def shouldBeF(value: T): F[Assertion] =
      leftSideValue.map(_ shouldBe value)
  }

  def effectTest[T](f: Effect[T])(implicit scheduler: Scheduler): T =
    f.value.unsafeRunSync(scheduler).right.get

  /** If a feature is missing we can do `Log[F].debug("FIXME: Implement feature X!")`
    * and add an assumption to the test that this has been done, otherwise cancel the test.
    * The test will automatically resume and pass once the log is removed.
    * This method is here so we have 1 way to check for the presence of these logs. */
  def cancelUntilFixed[F[_]](feature: String)(implicit log: LogStub[F], pos: source.Position) = {
    assert(feature.startsWith("FIXME:")) // So that we can find FIXME in code and test easily.
    val fixed = !log.debugs.contains(feature)
    if (!fixed) cancel(feature)
    // We could even fail the test if the feature is fixed, based on a system property setting,
    // to force removal of the assertion from the test.
    // That would be similar to how `pendingUntilFixed`, except with that one if you implement
    // a feature _badly_ you still just get a pending test instead of a failing one.
  }
}
