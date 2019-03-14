package io.casperlabs.comm.gossiping

import cats.effect._
import monix.tail.Iterant
import monix.reactive.Observable
import monix.execution.Scheduler

/** Convert an Observable to an Iterant and back in a resource safe way. */
trait ObservableIterant[F[_]] {
  def toObservable[A](it: Iterant[F, A]): Observable[A]
  def toIterant[A](obs: Observable[A]): Iterant[F, A]
}

object ObservableIterant {
  def apply[F[_]](implicit ev: ObservableIterant[F]) = ev

  implicit def default[F[_]: Effect](implicit scheduler: Scheduler) =
    new ObservableIterant[F] {
      def toObservable[A](it: Iterant[F, A]) =
        Observable.fromReactivePublisher(it.toReactivePublisher)

      def toIterant[A](obs: Observable[A]) =
        Iterant.fromReactivePublisher[F, A](obs.toReactivePublisher)
    }

  object syntax {
    implicit class `Iterant => Observable`[F[_], A](it: Iterant[F, A]) {
      def toObservable(implicit oi: ObservableIterant[F]) = oi.toObservable(it)
    }
    implicit class `Observable => Iterant`[A](obs: Observable[A]) {
      def toIterant[F[_]](implicit oi: ObservableIterant[F]) = oi.toIterant(obs)
    }
  }
}
