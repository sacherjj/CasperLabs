package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.casperlabs.casper.consensus.Block
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}

class MockDagStorage[F[_]: Monad](
    messagesRef: Ref[F, Map[BlockHash, Message]]
) extends DagStorage[F] {
  override val getRepresentation: F[DagRepresentation[F]] =
    (new MockDagRepresentation(): DagRepresentation[F]).pure[F]

  def insert(block: Block): F[DagRepresentation[F]] =
    messagesRef.update(_.updated(block.blockHash, Message.fromBlock(block).get)) >> getRepresentation

  override def checkpoint(): F[Unit] = ???
  override def clear(): F[Unit]      = ???
  override def close(): F[Unit]      = ???

  class MockDagRepresentation extends DagRepresentation[F] {
    override def lookup(blockHash: BlockHash) =
      messagesRef.get.map(_.get(blockHash))
    override def children(blockHash: BlockHash)                         = ???
    override def justificationToBlocks(blockHash: BlockHash)            = ???
    override def contains(blockHash: BlockHash)                         = ???
    override def topoSort(startBlockNumber: Long, endBlockNumber: Long) = ???
    override def topoSort(startBlockNumber: Long)                       = ???
    override def topoSortTail(tailLength: Int)                          = ???
    override def latestGlobal                                           = ???
    override def latestInEra(keyBlockHash: BlockHash)                   = ???

  }
}

object MockDagStorage {
  def apply[F[_]: Sync] =
    for {
      messagesRef <- Ref.of[F, Map[BlockHash, Message]](Map.empty)
    } yield new MockDagStorage(messagesRef)
}
