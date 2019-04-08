package io.casperlabs.comm.gossiping

import cats.effect._
import monix.tail.Iterant
import monix.reactive.Observable
import monix.execution.Scheduler
import simulacrum.typeclass
import scala.util.control.NonFatal

/** Convert an Observable to an Iterant and back in a resource safe way. */
@typeclass
trait ObservableIterant[F[_]] {
  def toObservable[A](it: Iterant[F, A]): Observable[A]
  def toIterant[A](obs: Observable[A]): Iterant[F, A]
}

object ObservableIterant {

  implicit def default[F[_]: Effect](implicit scheduler: Scheduler) =
    new ObservableIterant[F] {
      def toObservable[A](it: Iterant[F, A]) =
        Observable.fromReactivePublisher {
          it.onErrorRecoverWith {
            case NonFatal(ex) =>
              // Without this an `Iterant.liftF(Sync[F].raiseError(...))` would not complete the RPC.
              Iterant.raiseError[F, A](ex)
          }.toReactivePublisher
        }

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
