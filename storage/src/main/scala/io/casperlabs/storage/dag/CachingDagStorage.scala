package io.casperlabs.storage.dag

import cats._
import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.google.common.cache.{Cache, CacheBuilder}
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{MeteredDagRepresentation, MeteredDagStorage}

import scala.collection.JavaConverters._

class CachingDagStorage[F[_]: Sync](
    underlying: DagStorage[F] with DagRepresentation[F],
    private[dag] val childrenCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val justificationCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val blockSummariesCache: Cache[BlockHash, BlockSummary],
    semaphore: Semaphore[F]
) extends DagStorage[F]
    with DagRepresentation[F] {
  private def cacheOrUnderlying[A](fromCache: => Option[A], fromUnderlying: F[A]) =
    Sync[F].delay(fromCache) flatMap {
      case None    => fromUnderlying
      case Some(a) => a.pure[F]
    }

  private def cacheOrUnderlyingOpt[A](fromCache: => Option[A], fromUnderlying: F[Option[A]]) =
    Sync[F].delay(fromCache) flatMap {
      case None        => fromUnderlying
      case s @ Some(_) => (s: Option[A]).pure[F]
    }

  override def children(blockHash: BlockHash): F[Set[BlockHash]] =
    cacheOrUnderlying(
      Option(childrenCache.getIfPresent(blockHash)),
      underlying.children(blockHash)
    )

  /** Return blocks that having a specify justification */
  override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
    cacheOrUnderlying(
      Option(justificationCache.getIfPresent(blockHash)),
      underlying.justificationToBlocks(blockHash)
    )

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]

  override private[storage] def insert(block: Block): F[DagRepresentation[F]] =
    semaphore.withPermit(Sync[F].delay {
      val parents        = block.parentHashes
      val justifications = block.justifications.map(_.latestBlockHash)

      parents.foreach { parent =>
        val newChildren = Option(childrenCache.getIfPresent(parent))
          .getOrElse(Set.empty[BlockHash]) + block.blockHash
        childrenCache.put(parent, newChildren)
      }
      justifications.foreach { justification =>
        val newBlockHashes = Option(justificationCache.getIfPresent(justification))
          .getOrElse(Set.empty[BlockHash]) + block.blockHash
        justificationCache.put(justification, newBlockHashes)
      }
    }) >> underlying.insert(block)

  override def checkpoint(): F[Unit] = underlying.checkpoint()

  override def clear(): F[Unit] =
    Sync[F].delay {
      childrenCache.invalidateAll()
      justificationCache.invalidateAll()
    } >> underlying.clear()

  override def close(): F[Unit] = underlying.close()

  override def lookup(blockHash: BlockHash): F[Option[Message]] =
    cacheOrUnderlyingOpt(
      Option(blockSummariesCache.getIfPresent(blockHash))
        .flatMap(Message.fromBlockSummary(_).toOption),
      underlying.lookup(blockHash)
    )

  override def contains(blockHash: BlockHash): F[Boolean] =
    lookup(blockHash)
      .map(_.isDefined)
      .ifM(true.pure[F], underlying.contains(blockHash))

  /** Return the ranks of blocks in the DAG between start and end, inclusive. */
  override def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockHash]] = underlying.topoSort(startBlockNumber, endBlockNumber)

  /** Return ranks of blocks in the DAG from a start index to the end. */
  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]] =
    underlying.topoSort(startBlockNumber)

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockHash]] =
    underlying.topoSortTail(tailLength)

  override def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
    underlying.latestMessageHash(validator)

  override def latestMessage(validator: Validator): F[Option[Message]] =
    underlying.latestMessage(validator)

  override def latestMessageHashes: F[Map[Validator, BlockHash]] = underlying.latestMessageHashes

  override def latestMessages: F[Map[Validator, Message]] = underlying.latestMessages
}

object CachingDagStorage {
  def apply[F[_]: Concurrent: Metrics](
      underlying: DagStorage[F] with DagRepresentation[F],
      maxSizeBytes: Long,
      name: String = "cache"
  ): F[CachingDagStorage[F]] = {
    val metricsF = Metrics[F]
    val createBlockHashesSetCache = Sync[F].delay {
      CacheBuilder
        .newBuilder()
        .maximumWeight(maxSizeBytes)
        // Assuming block hashes 32 bytes long
        .weigher((_: BlockHash, values: Set[BlockHash]) => (values.size + 1) * 32)
        .build[BlockHash, Set[BlockHash]]()
    }

    val createBlockSummariesCache = Sync[F].delay {
      CacheBuilder
        .newBuilder()
        .maximumWeight(maxSizeBytes)
        .weigher((_: BlockHash, summary: BlockSummary) => summary.serializedSize)
        .build[BlockHash, BlockSummary]()
    }

    for {
      childrenCache       <- createBlockHashesSetCache
      justificationCache  <- createBlockHashesSetCache
      blockSummariesCache <- createBlockSummariesCache
      semaphore           <- Semaphore[F](1)
      store = new CachingDagStorage[F](
        underlying,
        childrenCache,
        justificationCache,
        blockSummariesCache,
        semaphore
      ) with MeteredDagStorage[F] with MeteredDagRepresentation[F] {
        override implicit val m: Metrics[F] = metricsF
        override implicit val ms: Metrics.Source =
          Metrics.Source(DagStorageMetricsSource, name)
        override implicit val a: Apply[F] = Sync[F]
      }
    } yield store
  }
}
