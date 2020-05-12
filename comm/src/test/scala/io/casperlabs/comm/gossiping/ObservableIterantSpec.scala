package io.casperlabs.comm.gossiping

import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import org.scalatest._
import monix.execution.Cancelable
import scala.concurrent.duration._

class ObservableIterantSpec extends FlatSpec {

  import Scheduler.Implicits.global

  behavior of "toIterant"

  it should "not have a race condition (TNET-23)" in {
    val obs = new Observable[Nothing] {
      override def unsafeSubscribeFn(subscriber: Subscriber[Nothing]): Cancelable = {
        subscriber.onComplete()
        Cancelable.empty
      }
    }

    val it = ObservableIterant.default[Task].toIterant(obs)

    it.toListL.runSyncUnsafe(1.second)
  }
}
