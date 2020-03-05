package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.casperlabs.casper.consensus.Block
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.block.BlockStorageWriter
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}, DagRepresentation.Validator
import io.casperlabs.storage.dag.EraTipRepresentation
import io.casperlabs.storage.BlockMsgWithTransform

class MockBlockDagStorage[F[_]: Monad](
    messagesRef: Ref[F, Map[BlockHash, Message]],
    // Just keep the last message from everyone, per era.
    latestRef: Ref[F, Map[BlockHash, Map[Validator, Message]]]
) extends BlockStorageWriter[F]
    with DagStorage[F] {
  override val getRepresentation: F[DagRepresentation[F]] =
    (new MockDagRepresentation(): DagRepresentation[F]).pure[F]

  override def put(blockHash: BlockHash, blockMsgWithTransform: BlockMsgWithTransform): F[Unit] =
    insert(blockMsgWithTransform.getBlockMessage).void

  override def insert(block: Block): F[DagRepresentation[F]] = {
    val message = Message.fromBlock(block).get
    messagesRef.update(_.updated(message.messageHash, message)) >>
      latestRef.update { m =>
        val k = message.eraId
        val v = message.validatorId
        m.updated(k, m(k).updated(v, message))
      } >>
      getRepresentation
  }

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
    override def getBlockInfosByValidator(
        validator: Validator,
        limit: Int,
        lastTimeStamp: Long,
        lastBlockHash: BlockHash
    )                         = ???
    override def latestGlobal = ???

    override def latestInEra(keyBlockHash: BlockHash) =
      new EraTipRepresentation[F] {
        override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
          latestMessage(validator).map(_.map(_.messageHash))

        override def latestMessage(validator: Validator): F[Set[Message]] =
          latestMessages.map(_.getOrElse(validator, Set.empty))

        override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
          latestMessages.map(_.mapValues(_.map(_.messageHash)))

        override def latestMessages: F[Map[Validator, Set[Message]]] =
          latestRef.get.map(m => m(keyBlockHash).mapValues(Set(_)))
      }.pure[F]

  }
}

object MockBlockDagStorage {
  def apply[F[_]: Sync](blocks: Block*): F[MockBlockDagStorage[F]] =
    for {
      messagesRef <- Ref.of[F, Map[BlockHash, Message]](Map.empty)
      latestRef <- Ref.of[F, Map[BlockHash, Map[Validator, Message]]](
                    Map.empty.withDefaultValue(Map.empty)
                  )
      storage = new MockBlockDagStorage(messagesRef, latestRef)
      _       <- blocks.toList.traverse(storage.insert)
    } yield storage
}
