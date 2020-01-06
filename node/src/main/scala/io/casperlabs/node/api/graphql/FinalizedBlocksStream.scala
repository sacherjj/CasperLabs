package io.casperlabs.node.api.graphql

import cats.effect._
import cats.effect.Concurrent
import cats.effect.implicits._
import cats.implicits._
import io.casperlabs.node.api.casper.StreamEventsRequest
import io.casperlabs.node.api.EventStream
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.shared.Log
import fs2._
import fs2.concurrent._
import monix.eval.{TaskLift, TaskLike}

trait FinalizedBlocksStream[F[_]] {
  def subscribe: Stream[F, BlockHash]
}

object FinalizedBlocksStream {

  def apply[F[_]](implicit FBS: FinalizedBlocksStream[F]): FinalizedBlocksStream[F] = FBS

  def of[F[_]: Concurrent: TaskLike: TaskLift: EventStream: Log](
      initLFB: BlockHash
  ): F[Resource[F, FinalizedBlocksStream[F]]] =
    for {
      // If readers are too slow, they will miss some messages, but that's okay
      // Since they interested only in the latest finalized messages.
      topic     <- CappedTopic[F, BlockHash](initLFB)
      maxQueued = 100
      subscription = TaskLift[F]
        .apply(
          EventStream[F]
            .subscribe(StreamEventsRequest(blockFinalized = true))
            .collect {
              case event if event.value.isNewFinalizedBlock =>
                event.getNewFinalizedBlock.blockHash
            }
            .consumeWith(monix.reactive.Consumer.foreachEval(topic.publish1(_)))
        )
        .start
        .tupleLeft(new FinalizedBlocksStream[F] {
          override def subscribe: Stream[F, BlockHash] = topic.subscribe(maxQueued)
        })
    } yield Resource.make(subscription)(_._2.cancel).map(_._1)
}
