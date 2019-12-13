package io.casperlabs.catscontrib

import java.util.concurrent.TimeoutException

import cats.effect.concurrent.Ref
import monix.eval.Task
import org.scalatest.{Assertion, FlatSpec}
import io.casperlabs.shared.LogStub
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import monix.execution.Scheduler.Implicits.global
import io.casperlabs.catscontrib.effect.implicits.fiberSyntax
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

class FiberSyntaxTest extends FlatSpec with Eventually {

  behavior of "FiberSyntaxTest"

  it should "log an error thrown in the forked fiber" in {
    implicit val logger   = LogStub[Task]()
    val errorMessage      = "BOOM"
    val error: Task[Unit] = Task.raiseError(new Throwable(errorMessage))

    error.forkAndLog.attempt.unsafeRunSync

    eventually(assert(logger.errors.exists(_.contains(errorMessage))))
  }

  implicit val patienceConfiguration: PatienceConfig = PatienceConfig(
    timeout = 2.seconds,
    interval = 50.millis
  )

  it should "really fork and not block main thread" in {
    implicit val logger = LogStub[Task]()

    val originalValue: Int = 0
    val fiberA: Int        = 10
    val fiberB: Int        = 100

    def assertContains[A](ref: Ref[Task, A], expected: A): Task[Assertion] =
      ref.get.map(v => assert(v == expected))

    val test = for {
      container <- Ref[Task].of(originalValue)
      _         <- container.set(fiberA).delayExecution(500.millis).forkAndLog
      // Test that container holds 0; forked fiber hasn't changed it yet.
      _ <- assertContains(container, originalValue)
      _ <- container.set(fiberB).forkAndLog.start
    } yield {
      eventually(assertContains(container, fiberB).unsafeRunSync)
      eventually(assertContains(container, fiberA).unsafeRunSync)
    }

    test.unsafeRunSync
  }

}
