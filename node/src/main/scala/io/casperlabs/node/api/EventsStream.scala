package io.casperlabs.node.api

import cats.effect._
import cats.implicits._
import io.casperlabs.casper.consensus.info.Event
import io.casperlabs.casper.EventEmitterContainer
import io.casperlabs.casper.consensus.info.Event.BlockAdded
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import io.casperlabs.storage.block.BlockStorage.BlockHash
import monix.execution.Scheduler.Implicits.global
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.ConcurrentSubject
import simulacrum.typeclass

@typeclass trait EventsStream[F[_]] extends EventEmitterContainer[F] {
  def subscribe(request: StreamEventsRequest): Observable[Event]
}

object EventsStream {
  def create[F[_]: Concurrent: FinalizedBlocksStream]: EventsStream[F] = {
    val source = ConcurrentSubject.publish[Event](OverflowStrategy.DropOld(bufferSize = 10))
    new EventsStream[F] {
      override def subscribe(request: StreamEventsRequest): Observable[Event] =
        source.filter { event: Event =>
          request match {
            case _ if request.addBlock => event.value.isBlockAdded
            case _                     => false
          }
        }

      override def blockAdded(blockHash: BlockHash): F[Unit] = {
        val event = Event().withBlockAdded(BlockAdded(blockHash))
        source.onNext(event)
        ().pure[F]
      }
    }
  }
}
