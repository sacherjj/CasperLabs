package io.casperlabs.node.api

import cats.effect._
import io.casperlabs.casper.consensus.info.{BlockInfo, Event}
import io.casperlabs.casper.EventEmitter
import io.casperlabs.casper.consensus.info.Event.{BlockAdded, Value}
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import monix.execution.Scheduler
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.ConcurrentSubject
import simulacrum.typeclass

@typeclass trait EventStream[F[_]] extends EventEmitter[F] {
  def subscribe(request: StreamEventsRequest): Observable[Event]
}

object EventStream {
  def create[F[_]: Concurrent: FinalizedBlocksStream](
      scheduler: Scheduler,
      eventStreamBufferSize: Int
  ): EventStream[F] = {
    val source =
      ConcurrentSubject.publish[Event](OverflowStrategy.DropOld(eventStreamBufferSize))(scheduler)
    new EventStream[F] {
      override def subscribe(request: StreamEventsRequest): Observable[Event] = {
        import Event.Value._
        source.filter {
          _.value match {
            case Empty               => false
            case Value.BlockAdded(_) => request.blockAdded
          }
        }
      }

      override def blockAdded(blockSummary: BlockSummary): F[Unit] =
        Sync[F].delay {
          val event = Event().withBlockAdded(BlockAdded().withBlock(blockSummary))
          source.onNext(event)
        }
    }
  }
}
