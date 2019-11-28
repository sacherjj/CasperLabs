package io.casperlabs.node.api

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import io.casperlabs.casper.EventEmitterContainer
import io.casperlabs.casper.consensus.info.Event
import fs2._
import fs2.concurrent._

trait EventsStream[F[_]] extends EventEmitterContainer[F] {
  def subscribe: Stream[F, Event]
}

object EventsStream {

  def apply[F[_]](implicit FBS: EventsStream[F]): EventsStream[F] = FBS

  def of[F[_]: Concurrent]: F[EventsStream[F]] =
    for {
      // If readers are too slow, they will miss some messages, but that's okay
      // Since they interested only in the latest finalized messages.
      deferredTopic <- Deferred[F, CappedTopic[F, Event]]
      deferredRef   <- Deferred[F, Ref[F, Event]]
      emptyRef      <- Ref.of[F, Boolean](true)
      maxQueued     = 100
    } yield {
      new EventsStream[F] {
        override def subscribe: Stream[F, Event] =
          Stream.eval(deferredTopic.get map (_.subscribe(maxQueued))).flatten

        override def set(event: Event): F[Unit] =
          for {
            isEmpty   <- emptyRef.get
            _         <- init(event).whenA(isEmpty)
            ref       <- deferredRef.get
            prevEvent <- ref.modify(prev => (event, prev))
            topic     <- deferredTopic.get
            _         <- topic.publish1(event).whenA(event != prevEvent)
          } yield ()

        override def get: F[Event] = deferredRef.get >>= (_.get)

        private def init(event: Event): F[Unit] =
          for {
            t <- CappedTopic[F, Event](event)
            r <- Ref.of[F, Event](event)
            _ <- deferredTopic.complete(t)
            _ <- deferredRef.complete(r)
            _ <- emptyRef.set(false)
          } yield ()
      }
    }
}
