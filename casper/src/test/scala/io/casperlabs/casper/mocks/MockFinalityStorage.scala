package io.casperlabs.casper.mocks

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.FinalityStorage

class MockFinalityStorage[F[_]: Monad](
    lastFinalizedRef: Ref[F, BlockHash],
    finalizedRef: Ref[F, (Set[BlockHash], Set[BlockHash])]
) extends FinalityStorage[F] {
  override def getLastFinalizedBlock: F[BlockHash] =
    lastFinalizedRef.get

  override def getFinalityStatus(block: BlockHash): F[FinalityStorage.FinalityStatus] =
    finalizedRef.get.map {
      case (fd, od) => FinalityStorage.FinalityStatus(fd.contains(block), od.contains(block))
    }

  override def markAsFinalized(
      mainParent: BlockHash,
      secondary: Set[BlockHash],
      orphaned: Set[BlockHash]
  ): F[Unit] =
    lastFinalizedRef.set(mainParent) >> finalizedRef.update {
      case (fd, od) =>
        (fd ++ secondary + mainParent, od ++ orphaned)
    }
}

object MockFinalityStorage {
  def apply[F[_]: Sync](blocks: BlockHash*): F[MockFinalityStorage[F]] =
    for {
      lastFinalizedRef <- Ref.of[F, BlockHash](blocks.last)
      finalizedRef     <- Ref.of[F, (Set[BlockHash], Set[BlockHash])]((blocks.toSet, Set.empty))
    } yield new MockFinalityStorage(lastFinalizedRef, finalizedRef)
}
