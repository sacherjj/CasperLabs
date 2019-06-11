package fs2.concurrent

import cats.effect.concurrent.Ref
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler.global
import monix.execution.schedulers.CanBlock.permit
import org.scalatest.{FunSuiteLike, Matchers}

import scala.concurrent.duration._
import scala.util.Random

class CappedTopicSuite extends FunSuiteLike with Matchers {

  test("CappedTopic should drop the oldest message if reached maximum queue size") {
    def loopSend(
        continueRef: Ref[Task, Boolean],
        topic: CappedTopic[Task, Int],
        sentCounter: Int
    ): Task[Int] =
      for {
        continue <- continueRef.get
        res <- if (continue)
                topic
                  .publish1(sentCounter)
                  .timeout(100.milliseconds)
                  .flatMap(_ => loopSend(continueRef, topic, sentCounter + 1))
              else Task(sentCounter)
      } yield res

    val test = for {
      topic       <- CappedTopic[Task, Int](Random.nextInt())
      stream      = topic.subscribe(1)
      _           <- stream.head.compile.toList
      continueRef <- Ref.of[Task, Boolean](true)
      fiber       <- loopSend(continueRef, topic, 0).attempt.start
      _           <- Task.sleep(1.seconds)
      _           <- continueRef.set(false)
      result      <- fiber.join
    } yield {
      result.isRight shouldBe true
      // Arbitrary number greater than 0
      // If it's greater than 0 then it means it was able to send messages without blocking
      result.right.get should be > 5
    }
    test.runSyncUnsafe(5.seconds)(global, permit)
  }
}
