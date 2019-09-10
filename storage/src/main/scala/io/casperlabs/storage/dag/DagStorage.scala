package io.casperlabs.storage.dag

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.metrics.Metered
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator

trait DagStorage[F[_]] {
  //TODO: Get rid of DagRepresentation if SQLite works out
  /* Doesn't guarantee to return immutable representation */
  def getRepresentation: F[DagRepresentation[F]]
  private[storage] def insert(block: Block): F[DagRepresentation[F]]
  def checkpoint(): F[Unit]
  def clear(): F[Unit]
  def close(): F[Unit]
}

object DagStorage {
  trait MeteredDagStorage[F[_]] extends DagStorage[F] with Metered[F] {

    abstract override def getRepresentation: F[DagRepresentation[F]] =
      incAndMeasure("representation", super.getRepresentation)

    abstract override def insert(block: Block): F[DagRepresentation[F]] =
      incAndMeasure("insert", super.insert(block))

    abstract override def checkpoint(): F[Unit] =
      incAndMeasure("checkpoint", super.checkpoint())
  }

  def apply[F[_]](implicit B: DagStorage[F]): DagStorage[F] = B
}

trait DagRepresentation[F[_]] {
  def children(blockHash: BlockHash): F[Set[BlockHash]]

  /** Return blocks that having a specify justification */
  def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]]
  def lookup(blockHash: BlockHash): F[Option[BlockSummary]]
  def contains(blockHash: BlockHash): F[Boolean]

  /** Return the ranks of blocks in the DAG between start and end, inclusive. */
  def topoSort(startBlockNumber: Long, endBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]]

  /** Return ranks of blocks in the DAG from a start index to the end. */
  def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]]

  def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockHash]]

  def latestMessageHash(validator: Validator): F[Option[BlockHash]]
  def latestMessage(validator: Validator): F[Option[BlockSummary]]
  def latestMessageHashes: F[Map[Validator, BlockHash]]
  def latestMessages: F[Map[Validator, BlockSummary]]
}

object DagRepresentation {
  type Validator = ByteString

  implicit class DagRepresentationRich[F[_]](
      dagRepresentation: DagRepresentation[F]
  ) {
    def getMainChildren(
        blockHash: BlockHash
    )(implicit monad: Monad[F]): F[List[BlockHash]] =
      dagRepresentation
        .children(blockHash)
        .flatMap(
          _.toList
            .filterA(
              child =>
                dagRepresentation.lookup(child).map {
                  // make sure child's main parent's hash equal to `blockHash`
                  case Some(blockSummary) => blockSummary.parents.head == blockHash
                  case None               => false
                }
            )
        )
  }

  def apply[F[_]](implicit ev: DagRepresentation[F]): DagRepresentation[F] = ev
}
