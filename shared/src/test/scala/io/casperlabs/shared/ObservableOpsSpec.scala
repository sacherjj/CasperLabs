package io.casperlabs.shared

import monix.eval.Task
import monix.reactive.Observable
import monix.execution.{ExecutionModel, Scheduler}
import org.scalatest._
import scala.concurrent.duration._
import scala.concurrent.TimeoutException

class ObservableOpsSpec extends WordSpec with Matchers {
  import ObservableOps._

  "withConsumerTimeout" when {
    // Restrict the client to request 1 item at a time.
    implicit val scheduler = Scheduler(ExecutionModel.BatchedExecution(1))

    "the consumer is slow" should {
      "cancel the stream" in {
        @volatile var cnt = 0
        val list = Observable
          .range(0, 100, 1)
          .doOnNext(i => Task.delay(cnt += 1))
          .withConsumerTimeout(100.millis)
          .mapEval(x => Task.pure(x).delayResult(250.millis))
          .toListL
          .attempt

        val res = list.runSyncUnsafe(2.seconds)
        res.isLeft shouldBe true
        res.left.get.getMessage shouldBe "Stream item not consumed within 100 milliseconds."
        cnt shouldBe 1
      }
    }

    "the consumer is fast" should {
      "not cancel the stream" in {
        val list = Observable
          .range(0, 100, 1)
          .withConsumerTimeout(100.millis)
          .toListL

        val res = list.runSyncUnsafe(2.seconds)
        res should have size 100
      }
    }
  }
}
