package io.casperlabs.blockstorage
import cats.Monad
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import io.casperlabs.blockstorage.BlockDagRepresentation.Validator
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.util.BlockMessageUtil.parentHashes
import io.casperlabs.blockstorage.util.TopologicalSortUtil
import io.casperlabs.casper.protocol.BlockMessage

import scala.collection.immutable.HashSet

final class InMemBlockDagStorage[F[_]: Monad: Sync](
    lock: Semaphore[F],
    latestMessagesRef: Ref[F, Map[Validator, BlockHash]],
    childMapRef: Ref[F, Map[BlockHash, Set[BlockHash]]],
    dataLookupRef: Ref[F, Map[BlockHash, BlockMetadata]],
    topoSortRef: Ref[F, Vector[Vector[BlockHash]]]
) extends BlockDagStorage[F] {
  final case class InMemBlockDagRepresentation(
      latestMessagesMap: Map[Validator, BlockHash],
      childMap: Map[BlockHash, Set[BlockHash]],
      dataLookup: Map[BlockHash, BlockMetadata],
      topoSortVector: Vector[Vector[BlockHash]]
  ) extends BlockDagRepresentation[F] {
    def children(blockHash: BlockHash): F[Option[Set[BlockHash]]] =
      Sync[F].delay { childMap.get(blockHash) }

    def lookup(blockHash: BlockHash): F[Option[BlockMetadata]] =
      Sync[F].delay { dataLookup.get(blockHash) }

    def contains(blockHash: BlockHash): F[Boolean] =
      Sync[F].delay { dataLookup.contains(blockHash) }

    def topoSort(startBlockNumber: Long): F[Vector[Vector[BlockHash]]] =
      Sync[F].delay { topoSortVector.drop(startBlockNumber.toInt) }

    def topoSortTail(tailLength: Int): F[Vector[Vector[BlockHash]]] =
      Sync[F].delay { topoSortVector.takeRight(tailLength) }

    def deriveOrdering(startBlockNumber: Long): F[Ordering[BlockMetadata]] =
      topoSort(startBlockNumber).map { topologicalSorting =>
        val order = topologicalSorting.flatten.zipWithIndex.toMap
        Ordering.by(b => order(b.blockHash))
      }

    def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
      Sync[F].delay { latestMessagesMap.get(validator) }

    def latestMessage(validator: Validator): F[Option[BlockMetadata]] =
      latestMessageHash(validator).flatMap {
        case Some(blockHash) => lookup(blockHash)
        case None            => Sync[F].pure(None)
      }

    def latestMessageHashes: F[Map[Validator, BlockHash]] =
      Sync[F].delay { latestMessagesMap }

    def latestMessages: F[Map[Validator, BlockMetadata]] =
      for {
        latestMessagesMapList <- Sync[F].delay(latestMessagesMap.toList)
        latestMessages <- latestMessagesMapList.traverse {
                           case (validator, hash) => lookup(hash).map(validator -> _.get)
                         }
      } yield latestMessages.toMap
  }

  override def getRepresentation: F[BlockDagRepresentation[F]] =
    for {
      _              <- lock.acquire
      latestMessages <- latestMessagesRef.get
      childMap       <- childMapRef.get
      dataLookup     <- dataLookupRef.get
      topoSort       <- topoSortRef.get
      _              <- lock.release
    } yield InMemBlockDagRepresentation(latestMessages, childMap, dataLookup, topoSort)
  override def insert(block: BlockMessage): F[Unit] =
    for {
      _ <- lock.acquire
      _ <- dataLookupRef.update(_.updated(block.blockHash, BlockMetadata.fromBlock(block)))
      _ <- childMapRef.update(
            childMap =>
              parentHashes(block).foldLeft(childMap) {
                case (acc, p) =>
                  val currChildren = acc.getOrElse(p, HashSet.empty[BlockHash])
                  acc.updated(p, currChildren + block.blockHash)
            }
          )
      _ <- topoSortRef.update(topoSort => TopologicalSortUtil.update(topoSort, 0L, block))
      _ <- latestMessagesRef.update(_.updated(block.sender, block.blockHash))
      _ <- lock.release
    } yield ()
  override def checkpoint(): F[Unit] = ().pure[F]
  override def clear(): F[Unit] =
    for {
      _ <- lock.acquire
      _ <- dataLookupRef.set(Map.empty)
      _ <- childMapRef.set(Map.empty)
      _ <- topoSortRef.set(Vector.empty)
      _ <- latestMessagesRef.set(Map.empty)
      _ <- lock.release
    } yield ()
  override def close(): F[Unit] = ().pure[F]
}

object InMemBlockDagStorage {
  def create[F[_]: Monad: Sync: Concurrent]: F[InMemBlockDagStorage[F]] =
    for {
      lock              <- Semaphore[F](1)
      latestMessagesRef <- Ref.of[F, Map[Validator, BlockHash]](Map.empty)
      childMapRef       <- Ref.of[F, Map[BlockHash, Set[BlockHash]]](Map.empty)
      dataLookupRef     <- Ref.of[F, Map[BlockHash, BlockMetadata]](Map.empty)
      topoSortRef       <- Ref.of[F, Vector[Vector[BlockHash]]](Vector.empty)
    } yield
      new InMemBlockDagStorage[F](
        lock,
        latestMessagesRef,
        childMapRef,
        dataLookupRef,
        topoSortRef
      )
}
