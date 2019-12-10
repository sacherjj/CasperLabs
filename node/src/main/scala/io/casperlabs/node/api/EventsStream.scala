package io.casperlabs.node.api

import cats.effect._
import cats.implicits._
import io.casperlabs.casper.consensus.info.Event
import io.casperlabs.casper.EventEmitterContainer
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import monix.execution.Scheduler.Implicits.global
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.ConcurrentSubject

trait EventsStream[F[_]] extends EventEmitterContainer[F] {
  def subscribe(request: StreamEventsRequest): Observable[Event]
}

object EventsStream {
  def apply[F[_]](implicit ev: EventsStream[F]): EventsStream[F] = ev

  def create[F[_]: Concurrent: FinalizedBlocksStream]: F[EventsStream[F]] = {
    val source = ConcurrentSubject.publish[Event](OverflowStrategy.DropOld(bufferSize = 10))
    new EventsStream[F] {
      override def subscribe(request: StreamEventsRequest): Observable[Event] =
        source.filter { event: Event =>
          request match {
            case _ if request.addBlock => event.value.isBlockAdded
            case _                     => false
          }
        }

      override def publish(event: Event): F[Unit] = {
        source.onNext(event)
        ().pure[F]
      }
    }.pure[F]
  }
}
