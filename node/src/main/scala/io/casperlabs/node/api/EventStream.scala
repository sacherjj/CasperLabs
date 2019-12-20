package io.casperlabs.node.api

import cats.effect._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.apply._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.info.{BlockInfo, Event}
import io.casperlabs.casper.{EventEmitter, LastFinalizedBlockHashContainer}
import io.casperlabs.casper.consensus.info.Event.{BlockAdded, Value}
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.deploy.DeployStorage
import monix.execution.Scheduler
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.ConcurrentSubject
import simulacrum.typeclass
import io.casperlabs.shared.Log
import io.casperlabs.catscontrib.effect.implicits.fiberSyntax

@typeclass trait EventStream[F[_]] extends EventEmitter[F] {
  def subscribe(request: StreamEventsRequest): Observable[Event]
}

object EventStream {
  def create[F[_]: Concurrent: DeployStorage: BlockStorage: Log: Metrics](
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

      override def blockAdded(blockInfo: BlockInfo): F[Unit] =
        Sync[F].delay {
          val event = Event().withBlockAdded(BlockAdded().withBlock(blockInfo))
          source.onNext(event)
        }

      override def newLFB(lfb: BlockHash, indirectlyFinalized: Set[BlockHash]): F[Unit] =
        DeployBuffer.removeFinalizedDeploys(indirectlyFinalized + lfb).forkAndLog
    }
  }
}
