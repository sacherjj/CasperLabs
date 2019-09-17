package io.casperlabs.storage.dag

import cats.Apply
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.shared.Log
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.MeteredDagStorage
import io.casperlabs.storage.util.TopologicalSortUtil
import io.casperlabs.storage.{BlockValidatorIsMalformed, DagStorageMetricsSource}

import scala.collection.immutable.HashSet

@silent("The outer reference in this type test cannot be checked at run time.")
class InMemDagStorage[F[_]: MonadThrowable: Log: BlockStorage](
    lock: Semaphore[F],
    latestMessagesRef: Ref[F, Map[Validator, BlockHash]],
    childMapRef: Ref[F, Map[BlockHash, Set[BlockHash]]],
    justificationMapRef: Ref[F, Map[BlockHash, Set[BlockHash]]],
    dataLookupRef: Ref[F, Map[BlockHash, BlockSummary]],
    topoSortRef: Ref[F, Vector[Vector[BlockHash]]]
) extends DagStorage[F] {
  final case class InMemDagRepresentation(
      latestMessagesMap: Map[Validator, BlockHash],
      childMap: Map[BlockHash, Set[BlockHash]],
      justificationMap: Map[BlockHash, Set[BlockHash]],
      dataLookup: Map[BlockHash, BlockSummary],
      topoSortVector: Vector[Vector[BlockHash]]
  ) extends DagRepresentation[F] {
    def children(blockHash: BlockHash): F[Set[BlockHash]] =
      childMap.getOrElse(blockHash, Set.empty).pure[F]
    def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
      justificationMap.getOrElse(blockHash, Set.empty).pure[F]
    def lookup(blockHash: BlockHash): F[Option[Message]] =
      Message.fromOptionalSummary[F](dataLookup.get(blockHash))
    def contains(blockHash: BlockHash): F[Boolean] =
      dataLookup.contains(blockHash).pure[F]
    def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]] =
      topoSort(startBlockNumber, topoSortVector.size.toLong - 1)
    def topoSort(startBlockNumber: Long, endBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]] =
      fs2.Stream.emits[F, Vector[BlockHash]](
        topoSortVector
          .slice(startBlockNumber.toInt, endBlockNumber.toInt + 1)
      )

    def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockHash]] =
      fs2.Stream.emits[F, Vector[BlockHash]](topoSortVector.takeRight(tailLength))
    def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
      latestMessagesMap.get(validator).pure[F]
    def latestMessage(validator: Validator): F[Option[Message]] =
      latestMessagesMap.get(validator).flatTraverse(lookup)
    def latestMessageHashes: F[Map[Validator, BlockHash]] =
      latestMessagesMap.pure[F]
    def latestMessages: F[Map[Validator, Message]] =
      latestMessagesMap.toList
        .traverse {
          case (validator, hash) => lookup(hash).map(validator -> _.get)
        }
        .map(_.toMap)
  }

  override def getRepresentation: F[DagRepresentation[F]] =
    for {
      _                <- lock.acquire
      latestMessages   <- latestMessagesRef.get
      childMap         <- childMapRef.get
      justificationMap <- justificationMapRef.get
      dataLookup       <- dataLookupRef.get
      topoSort         <- topoSortRef.get
      _                <- lock.release
    } yield InMemDagRepresentation(
      latestMessages,
      childMap,
      justificationMap,
      dataLookup,
      topoSort
    )

  override def insert(block: Block): F[DagRepresentation[F]] =
    for {
      _ <- lock.acquire
      _ <- dataLookupRef.update(_.updated(block.blockHash, BlockSummary.fromBlock(block)))
      _ <- childMapRef.update(
            childMap =>
              block.getHeader.parentHashes.foldLeft(childMap) {
                case (acc, p) =>
                  val currChildren = acc.getOrElse(p, HashSet.empty[BlockHash])
                  acc.updated(p, currChildren + block.blockHash)
              }
          )
      _ <- justificationMapRef.update(
            justificationMap =>
              block.getHeader.justifications.foldLeft(justificationMap) {
                case (acc, justification) =>
                  val currBlocksWithJustification =
                    acc.getOrElse(justification.latestBlockHash, HashSet.empty[BlockHash])
                  acc.updated(
                    justification.latestBlockHash,
                    currBlocksWithJustification + block.blockHash
                  )
              }
          )
      _ <- topoSortRef.update(topoSort => TopologicalSortUtil.update(topoSort, 0L, block))
      newValidators = block.getHeader.getState.bonds
        .map(_.validatorPublicKey)
        .toSet
        .diff(block.getHeader.justifications.map(_.validatorPublicKey).toSet)
      validator = block.getHeader.validatorPublicKey
      _ <- Log[F]
            .warn(
              s"Block ${Base16.encode(block.blockHash.toByteArray)} validator is empty"
            )
            .whenA(validator.isEmpty)
      _ <- MonadThrowable[F]
            .raiseError[Set[ByteString]](BlockValidatorIsMalformed(block))
            .whenA(!validator.isEmpty && validator.size != 32)
      toUpdateValidators = if (validator.isEmpty) {
        // For Genesis, all validators are "new".
        newValidators
      } else {
        // For any other block, only validator that produced it
        // needs to have its "latest message" updated.
        List(validator)
      }
      _ <- latestMessagesRef.update { latestMessages =>
            toUpdateValidators.foldLeft(latestMessages) {
              case (acc, v) => acc.updated(v, block.blockHash)
            }
          }
      _   <- lock.release
      dag <- getRepresentation
    } yield dag

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

object InMemDagStorage {
  def create[F[_]: Concurrent: Log: BlockStorage](
      implicit met: Metrics[F]
  ): F[InMemDagStorage[F]] =
    for {
      lock                        <- Semaphore[F](1)
      latestMessagesRef           <- Ref.of[F, Map[Validator, BlockHash]](Map.empty)
      childMapRef                 <- Ref.of[F, Map[BlockHash, Set[BlockHash]]](Map.empty)
      justificationToBlocksMapRef <- Ref.of[F, Map[BlockHash, Set[BlockHash]]](Map.empty)
      dataLookupRef               <- Ref.of[F, Map[BlockHash, BlockSummary]](Map.empty)
      topoSortRef                 <- Ref.of[F, Vector[Vector[BlockHash]]](Vector.empty)
    } yield new InMemDagStorage[F](
      lock,
      latestMessagesRef,
      childMapRef,
      justificationToBlocksMapRef,
      dataLookupRef,
      topoSortRef
    ) with MeteredDagStorage[F] {
      override implicit val m: Metrics[F] = met
      override implicit val ms: Source    = Metrics.Source(DagStorageMetricsSource, "in-mem")
      override implicit val a: Apply[F]   = Concurrent[F]
    }
}
