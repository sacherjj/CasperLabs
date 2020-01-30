package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import io.casperlabs.casper.EventEmitter
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.info.{BlockInfo, Event}

class MockEventEmitter[F[_]](
    eventsRef: Ref[F, Vector[Event]]
) extends EventEmitter[F] {
  def events = eventsRef.get

  override def blockAdded(block: BlockInfo): F[Unit] =
    eventsRef.update {
      _ :+ Event().withBlockAdded(Event.BlockAdded().withBlock(block))
    }

  override def newLastFinalizedBlock(
      lfb: BlockHash,
      indirectlyFinalized: Set[BlockHash]
  ): F[Unit] =
    eventsRef.update {
      _ :+ Event().withNewFinalizedBlock(Event.NewFinalizedBlock(lfb, indirectlyFinalized.toSeq))
    }
}

object MockEventEmitter {
  def unsafe[F[_]: Sync] = new MockEventEmitter[F](Ref.unsafe[F, Vector[Event]](Vector.empty))
}
