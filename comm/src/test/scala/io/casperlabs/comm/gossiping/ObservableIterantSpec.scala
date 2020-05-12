package io.casperlabs.comm.gossiping

import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import monix.tail.Iterant
import org.scalatest._
import scala.concurrent.duration._
import scala.util.control.NonFatal

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
