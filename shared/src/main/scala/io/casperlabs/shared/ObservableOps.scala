package io.casperlabs.shared

import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.{Observable, Observer}
import monix.reactive.observers.{SafeSubscriber, Subscriber}
import monix.eval.Task
import scala.concurrent.{Future, TimeoutException}
import scala.concurrent.duration.FiniteDuration

object ObservableOps {
  implicit class RichObservable[A](obs: Observable[A]) {

    /** Cancel the stream if the consumer is taking too long to pull items. */
    def withConsumerTimeout(timeout: FiniteDuration) =
      new Observable[A] {

        override def unsafeSubscribeFn(subscriber: Subscriber[A]): Cancelable = {
          implicit val scheduler = subscriber.scheduler
          // Using a SafeSubscriber so we can call onError while onNext is running.
          val ss = SafeSubscriber(subscriber)
          val cancel: Task[Ack] = Task
            .delay {
              // After the onNext is finished the subscriber will get error.
              ss.onError(new TimeoutException(s"Stream item not consumed within $timeout."))
              // While we can cancel the source observable rigth now.
              Ack.Stop
            }
            .delayExecution(timeout)

          obs.subscribe {
            new Observer[A] {
              override def onComplete() =
                ss.onComplete()

              override def onError(ex: Throwable) =
                ss.onError(ex)

              override def onNext(elem: A): Future[Ack] =
                Task
                  .race(cancel, Task.fromFuture(ss.onNext(elem)))
                  .map(_.merge)
                  .runToFuture
            }
          }
        }
      }
  }
}
