package io.casperlabs.storage.dag

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.metrics.Metered
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16

trait DagStorage[F[_]] {

  /** Doesn't guarantee to return immutable representation. */
  def getRepresentation: F[DagRepresentation[F]]

  /** Insert a block into the DAG and update the latest messages.
    *
    * If the key block hash corresponds to an era, then update descendant eras as well,
    * so that they can pick up blocks that arrived late and incorporate them into their DAG.
    * Ballots that only arrive to finalize switch blocks are not propagated to child eras.
    */
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

    abstract override def children(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("children", super.children(blockHash))

    abstract override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("justificationToBlocks", super.justificationToBlocks(blockHash))

    abstract override def lookup(blockHash: BlockHash): F[Option[Message]] =
      incAndMeasure("lookup", super.lookup(blockHash))

    abstract override def contains(blockHash: BlockHash): F[Boolean] =
      incAndMeasure("contains", super.contains(blockHash))

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

  trait MeteredTipRepresentation[F[_]] extends TipRepresentation[F] with Metered[F] {
    abstract override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      incAndMeasure("latestMessageHash", super.latestMessageHash(validator))

    abstract override def latestMessage(validator: Validator): F[Set[Message]] =
      incAndMeasure("latestMessage", super.latestMessage(validator))

    abstract override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      incAndMeasure("latestMessageHashes", super.latestMessageHashes)

    abstract override def latestMessages: F[Map[Validator, Set[Message]]] =
      incAndMeasure("latestMessages", super.latestMessages)
  }

  def apply[F[_]](implicit B: DagStorage[F]): DagStorage[F] = B
}

trait TipRepresentation[F[_]] {
  def latestMessageHash(validator: Validator): F[Set[BlockHash]]
  def latestMessage(validator: Validator): F[Set[Message]]
  def latestMessageHashes: F[Map[Validator, Set[BlockHash]]]
  def latestMessages: F[Map[Validator, Set[Message]]]
}

trait EraTipRepresentation[F[_]] extends TipRepresentation[F] {
  // TODO: These methods should move here from DagRepresentationRich,
  // to make sure we never try to detect equivocators on the global
  // representation, however for that we need lots of updates in
  // the casper codebase.

  // def getEquivocators: F[Set[Validator]] = ???
  // def getEquivocations: F[Map[Validator, Set[Message]]] = ???
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

  /** Get a global representation, which can be used in:
    * 1) naive casper mode, without eras
    * 2) in the gossiping, when nodes ask each other for their latest blocks
    *
    * Latest messages would be that of any era which is considered active,
    * so that pull based gossiping can pull ballots of eras still being finalized
    * as well as the child era which is already started, but we stop returning
    * records for eras that have already finished (their last ballots are no longer
    * relevant tips).
    *
    * This will not reflect equivocations in the presence of parallel eras.
    *
    * Doesn't guarantee to return immutable representation.
    */
  def latestGlobal: F[TipRepresentation[F]]

  /** Get a representation restricted to a given era, which means the latest messages
    * of the ancestor eras affect the child eras, but not vice versa. Latest messages
    * in sibling eras are also invisible to each other. The DAG itself, i.e. the parent
    * child relationships are unaffected.
    */
  def latestInEra(keyBlockHash: BlockHash): F[EraTipRepresentation[F]]
}

object DagRepresentation {
  type Validator = ByteString

  implicit class DagRepresentationRich[F[_]: Monad](
      dagRepresentation: DagRepresentation[F]
  ) {
    def getMainChildren(
        blockHash: BlockHash
    ): F[List[BlockHash]] =
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
    def getEquivocators: F[Set[Validator]] =
      getEquivocations.map(_.keySet)

    // NOTE: These extension methods are here so the Naive-Casper codebase doesn't have to do another
    // step (i.e. `.latestGlobal.flatMap { tip => ... }`)  but in Highway we should first specify the era.

    def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessageHash(validator))

    def latestMessage(validator: Validator): F[Set[Message]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessage(validator))

    def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessageHashes)

    def latestMessages: F[Map[Validator, Set[Message]]] =
      dagRepresentation.latestGlobal.flatMap(_.latestMessages)

    // Returns a mapping between equivocators and their messages.
    def getEquivocations: F[Map[Validator, Set[Message]]] =
      latestMessages.map(_.filter(_._2.size > 1))

    // Returns latest messages from honest validators
    def latestMessagesHonestValidators: F[Map[Validator, Message]] =
      latestMessages.map { latestMessages =>
        latestMessages.collect {
          case (v, messages) if messages.size == 1 =>
            (v, messages.head)
        }
      }
  }

  def apply[F[_]](implicit ev: DagRepresentation[F]): DagRepresentation[F] = ev
}
