package io.casperlabs.casper.mocks

import cats._
import cats.implicits._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import io.casperlabs.casper.{DeployHash, EventEmitter}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.info.{BlockInfo, Event}
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}

class MockEventEmitter[F[_]: Applicative](
    eventsRef: Ref[F, Vector[Event]]
) extends EventEmitter[F] {
  def events = eventsRef.get

  private def add(event: Event) =
    eventsRef.update(_ :+ event)

  override def blockAdded(blockHash: BlockHash): F[Unit] =
    add(
      Event().withBlockAdded(
        Event.BlockAdded().withBlock(BlockInfo().withSummary(BlockSummary(blockHash)))
      )
    )

  override def newLastFinalizedBlock(
      lfb: BlockHash,
      indirectlyFinalized: Set[BlockHash],
      indirectlyOrphaned: Set[BlockHash]
  ): F[Unit] =
    add(
      Event().withNewFinalizedBlock(
        Event.NewFinalizedBlock(lfb, indirectlyFinalized.toSeq, indirectlyOrphaned.toSeq)
      )
    )

  override def deployAdded(deploy: Deploy): F[Unit] =
    add(Event().withDeployAdded(Event.DeployAdded().withDeploy(deploy)))

  override def deploysDiscarded(deployHashesWithReasons: Seq[(DeployHash, String)]): F[Unit] =
    deployHashesWithReasons.toList
      .map {
        case (deployHash, message) =>
          Event().withDeployDiscarded(
            Event.DeployDiscarded().withDeploy(Deploy(deployHash)).withMessage(message)
          )
      }
      .traverse(add(_))
      .void

  override def deploysRequeued(deployHashes: Seq[DeployHash]): F[Unit] =
    deployHashes.toList
      .map { deployHash =>
        Event().withDeployRequeued(Event.DeployRequeued().withDeploy(Deploy(deployHash)))
      }
      .traverse(add(_))
      .void

}

object MockEventEmitter {
  def unsafe[F[_]: Sync] = new MockEventEmitter[F](Ref.unsafe[F, Vector[Event]](Vector.empty))
}
