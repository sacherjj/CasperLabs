package io.casperlabs.node.api.graphql

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.LastFinalizedBlockHashContainer
import fs2._
import fs2.concurrent._

trait FinalizedBlocksStream[F[_]] extends LastFinalizedBlockHashContainer[F] {
  def subscribe: Stream[F, BlockHash]
}

object FinalizedBlocksStream {

  def apply[F[_]](implicit FBS: FinalizedBlocksStream[F]): FinalizedBlocksStream[F] = FBS

  def of[F[_]: Concurrent]: F[FinalizedBlocksStream[F]] =
    for {
      // If readers are too slow, they will miss some messages, but that's okay
      // Since they interested only in the latest finalized messages.
      deferredTopic <- Deferred[F, CappedTopic[F, BlockHash]]
      deferredRef   <- Deferred[F, Ref[F, BlockHash]]
      emptyRef      <- Ref.of[F, Boolean](true)
      maxQueued     = 100
    } yield {
      new FinalizedBlocksStream[F] {
        override def subscribe: Stream[F, BlockHash] =
          Stream.eval(deferredTopic.get map (_.subscribe(maxQueued))).flatten

        override def set(blockHash: BlockHash): F[Unit] =
          for {
            isEmpty       <- emptyRef.get
            _             <- init(blockHash).whenA(isEmpty)
            ref           <- deferredRef.get
            prevBlockHash <- ref.modify(prev => (blockHash, prev))
            topic         <- deferredTopic.get
            _             <- topic.publish1(blockHash).whenA(blockHash != prevBlockHash)
          } yield ()

        override def get: F[BlockHash] = deferredRef.get >>= (_.get)

        private def init(blockHash: BlockHash): F[Unit] =
          for {
            t <- CappedTopic[F, BlockHash](blockHash)
            r <- Ref.of[F, BlockHash](blockHash)
            _ <- deferredTopic.complete(t)
            _ <- deferredRef.complete(r)
            _ <- emptyRef.set(false)
          } yield ()
      }
    }
}
