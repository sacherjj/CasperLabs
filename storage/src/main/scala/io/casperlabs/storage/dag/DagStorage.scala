package io.casperlabs.storage.dag

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.metrics.Metered
import io.casperlabs.models.Message
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16

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

  trait MeteredDagRepresentation[F[_]] extends DagRepresentation[F] with Metered[F] {
    // Not measuring 'latestMessage*' because they return fs2.Stream which doesn't work with 'incAndMeasure'

    abstract override def children(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("children", super.children(blockHash))

    abstract override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("justificationToBlocks", super.justificationToBlocks(blockHash))

    abstract override def lookup(blockHash: BlockHash): F[Option[Message]] =
      incAndMeasure("lookup", super.lookup(blockHash))

    abstract override def contains(blockHash: BlockHash): F[Boolean] =
      incAndMeasure("contains", super.contains(blockHash))

    abstract override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      incAndMeasure("latestMessageHash", super.latestMessageHash(validator))

    abstract override def latestMessage(validator: Validator): F[Set[Message]] =
      incAndMeasure("latestMessage", super.latestMessage(validator))

    abstract override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      incAndMeasure("latestMessageHashes", super.latestMessageHashes)

    abstract override def latestMessages: F[Map[Validator, Set[Message]]] =
      incAndMeasure("latestMessages", super.latestMessages)

    abstract override def topoSort(
        startBlockNumber: Long,
        endBlockNumber: Long
    ): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure(
        "topoSort",
        super
          .topoSort(startBlockNumber, endBlockNumber)
      )

    abstract override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure("topoSort", super.topoSort(startBlockNumber))

    abstract override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]] =
      super.incAndMeasure("topoSortTail", super.topoSortTail(tailLength))
  }

  def apply[F[_]](implicit B: DagStorage[F]): DagStorage[F] = B
}

trait DagRepresentation[F[_]] {
  def children(blockHash: BlockHash): F[Set[BlockHash]]

  /** Return blocks that having a specify justification */
  def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]]
  def lookup(blockHash: BlockHash): F[Option[Message]]
  def contains(blockHash: BlockHash): F[Boolean]

  def lookupUnsafe(blockHash: BlockHash)(implicit MT: MonadThrowable[F]): F[Message] =
    lookup(blockHash) flatMap {
      MonadThrowable[F].fromOption(
        _,
        new NoSuchElementException(
          s"DAG store was missing ${Base16.encode(blockHash.toByteArray)}."
        )
      )
    }

  /** Return block summaries with ranks in the DAG between start and end, inclusive. */
  def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockInfo]]

  /** Return block summaries with ranks of blocks in the DAG from a start index to the end. */
  def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]]

  def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]]

  def latestMessageHash(validator: Validator): F[Set[BlockHash]]
  def latestMessage(validator: Validator): F[Set[Message]]
  def latestMessageHashes: F[Map[Validator, Set[BlockHash]]]
  def latestMessages: F[Map[Validator, Set[Message]]]
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

    // Returns a set of validators that this node has seen equivocating.
    def getEquivocators(implicit M: Monad[F]): F[Set[Validator]] =
      getEquivocations.map(_.keySet)

    // Returns a mapping between equivocators and their messages.
    def getEquivocations(implicit M: Monad[F]): F[Map[Validator, Set[Message]]] =
      dagRepresentation.latestMessages.map(_.filter(_._2.size > 1))

    // Returns latest messages from honest validators
    def latestMessagesHonestValidators(implicit M: Monad[F]): F[Map[Validator, Message]] =
      dagRepresentation.latestMessages.map { latestMessages =>
        latestMessages.collect {
          case (v, messages) if messages.size == 1 =>
            (v, messages.head)
        }
      }
  }

  def apply[F[_]](implicit ev: DagRepresentation[F]): DagRepresentation[F] = ev
}
