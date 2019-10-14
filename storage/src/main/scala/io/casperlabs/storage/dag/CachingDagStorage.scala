package io.casperlabs.storage.dag

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import com.google.common.cache.{Cache, CacheBuilder, RemovalListener, RemovalNotification}
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{MeteredDagRepresentation, MeteredDagStorage}

import scala.collection.immutable.{NumericRange, SortedSet => ImmutableSortedSet}
import scala.collection.mutable.{SortedSet => MutableSortedSet}
import scala.math.{max, min}

class CachingDagStorage[F[_]: Concurrent](
    // How far to go to the past (by ranks) for caching neighborhood of looked up block
    neighborhoodBefore: Int,
    // How far to go to the future (by ranks) for caching neighborhood of looked up block
    neighborhoodAfter: Int,
    underlying: DagStorage[F] with DagRepresentation[F],
    private[dag] val childrenCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val justificationCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val messagesCache: Cache[BlockHash, Message],
    // Should contain only disjoint ranges, represents rank ranges of cached neighbors
    private[dag] var ranksRanges: MutableSortedSet[NumericRange.Inclusive[Long]],
    semaphore: Semaphore[F]
) extends DagStorage[F]
    with DagRepresentation[F] {
  import CachingDagStorage.{rangeOrdering, rangesSemigroup}

  /** Unsafe to be invoked concurrently */
  private def unsafeUpdateRanges(newRange: NumericRange.Inclusive[Long]): F[Unit] =
    Sync[F].delay(ranksRanges = ranksRanges |+| MutableSortedSet(newRange))

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

  /** Unsafe to be invoked concurrently */
  private def unsafeCacheMessage(message: Message): F[Unit] = Sync[F].delay {
    val parents        = message.parents
    val justifications = message.justifications.map(_.latestBlockHash)
    parents.foreach { parent =>
      val newChildren = Option(childrenCache.getIfPresent(parent))
        .getOrElse(Set.empty[BlockHash]) + message.messageHash
      childrenCache.put(parent, newChildren)
    }
    justifications.foreach { justification =>
      val newBlockHashes = Option(justificationCache.getIfPresent(justification))
        .getOrElse(Set.empty[BlockHash]) + message.messageHash
      justificationCache.put(justification, newBlockHashes)
    }
    messagesCache.put(message.messageHash, message)
  }

  /** Unsafe to be invoked concurrently */
  private def unsafeCacheSummary(summary: BlockSummary): F[Unit] =
    Sync[F].fromTry(Message.fromBlockSummary(summary)).flatMap(unsafeCacheMessage)

  /** Unsafe to be invoked concurrently.
    * Reads from [[underlying]] only missing neighbors.
    * Calculates them using ranks ranges kept in [[ranksRanges]].
    * */
  private def unsafeCacheNeighborhood(m: Message): F[Unit] = {
    val newRanks: ImmutableSortedSet[Long] = ImmutableSortedSet(
      (m.rank - neighborhoodBefore).to(m.rank + neighborhoodAfter): _*
    ).diff(ImmutableSortedSet(ranksRanges.toList.flatMap(_.toList): _*))

    val rangesToQuery: List[NumericRange.Inclusive[Long]] = if (newRanks.nonEmpty) {
      val (r, start, end) =
        newRanks.tail.foldLeft(
          (ImmutableSortedSet.empty[NumericRange.Inclusive[Long]], newRanks.head, newRanks.head)
        ) {
          case ((acc, start, prev), next) if next == prev + 1 =>
            (acc, start, next)
          case ((acc, start, prev), next) =>
            (acc + start.to(prev), next, next)
        }
      (r + start.to(end)).toList
    } else {
      Nil
    }

    val cacheNeighbors = fs2.Stream
      .emits[F, NumericRange.Inclusive[Long]](rangesToQuery)
      .flatMap { range =>
        topoSort(range.start, range.end)
      }
      .compile
      .toList
      .flatMap(summaries => summaries.flatten.traverse_(unsafeCacheSummary))

    val updateRanges = rangesToQuery.traverse_(unsafeUpdateRanges)

    cacheNeighbors >> updateRanges
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
    for {
      dag     <- underlying.insert(block)
      message <- Sync[F].fromTry(Message.fromBlock(block))
      _       <- semaphore.withPermit(unsafeCacheMessage(message))
    } yield dag

  override def checkpoint(): F[Unit] = underlying.checkpoint()

  override def clear(): F[Unit] =
    Sync[F].delay {
      childrenCache.invalidateAll()
      justificationCache.invalidateAll()
    } >> underlying.clear()

  override def close(): F[Unit] = underlying.close()

  override def lookup(blockHash: BlockHash): F[Option[Message]] =
    cacheOrUnderlyingOpt(
      Option(messagesCache.getIfPresent(blockHash)),
      underlying
        .lookup(blockHash)
        .flatMap(
          maybeMessage =>
            semaphore.withPermit(maybeMessage.traverse_(unsafeCacheNeighborhood)) >>
              maybeMessage.pure[F]
        )
    )

  override def contains(blockHash: BlockHash): F[Boolean] =
    lookup(blockHash)
      .map(_.isDefined)
      .ifM(true.pure[F], underlying.contains(blockHash))

  /** Return the ranks of blocks in the DAG between start and end, inclusive. */
  override def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockSummary]] =
    underlying.topoSort(startBlockNumber, endBlockNumber)

  /** Return ranks of blocks in the DAG from a start index to the end. */
  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockSummary]] =
    underlying.topoSort(startBlockNumber)

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockSummary]] =
    underlying.topoSortTail(tailLength)

  override def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
    underlying.latestMessageHash(validator)

  override def latestMessage(validator: Validator): F[Option[Message]] =
    underlying.latestMessage(validator)

  override def latestMessageHashes: F[Map[Validator, BlockHash]] = underlying.latestMessageHashes

  override def latestMessages: F[Map[Validator, Message]] = underlying.latestMessages
}

object CachingDagStorage {
  /* Should be used only for disjoint ranges */
  private[dag] implicit val rangeOrdering: Ordering[NumericRange.Inclusive[Long]] =
    (x: NumericRange.Inclusive[Long], y: NumericRange.Inclusive[Long]) => x.start.compareTo(y.start)

  /** Should be used only for disjoint sets of ranges.
    * Creates a new mutable sorted set with combined ranges if they are overlapping.
    * Produced set contains only disjoint ranges. */
  private[dag] implicit val rangesSemigroup
      : Semigroup[MutableSortedSet[NumericRange.Inclusive[Long]]] =
    (
        x: MutableSortedSet[NumericRange.Inclusive[Long]],
        y: MutableSortedSet[NumericRange.Inclusive[Long]]
    ) => {
      val z       = MutableSortedSet(x.union(y).toSeq: _*)
      var updated = false

      do {
        updated = false
        for {
          a <- z
          b <- z
          if a != b
        } {
          val overlapping = b.start <= (a.end + 1) && b.end >= (a.start - 1)
          if (overlapping) {
            val c = min(a.start, b.start).to(max(a.end, b.end))
            z -= a
            z -= b
            z += c
            updated = true
          }
        }
      } while (updated)

      MutableSortedSet(z.toSeq: _*)
    }

  def apply[F[_]: Concurrent: Metrics](
      underlying: DagStorage[F] with DagRepresentation[F],
      maxSizeBytes: Long,
      // How far to go to the past (by ranks) for caching neighborhood of looked up block
      neighborhoodBefore: Int,
      // How far to go to the future (by ranks) for caching neighborhood of looked up block
      neighborhoodAfter: Int,
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

    /** Updates [[ranksRanges]] when a message is evicted from cache.
      * Otherwise [[CachingDagStorage]] would think that the message is cached even after its eviction.
      *
      * Not 100% optimal, because we mark whole rank as uncached,
      * even it's still may contain messages with the same rank. */
    def createMessageRemovalListener(
        ranksRanges: MutableSortedSet[NumericRange.Inclusive[Long]]
    ): RemovalListener[BlockHash, Message] = { n: RemovalNotification[BlockHash, Message] =>
      Option(n.getValue)
        .foreach { m =>
          synchronized {
            ranksRanges
              .find(r => m.rank >= r.start && m.rank <= r.end)
              .foreach { rangeToDelete =>
                val updatedRanges = if (rangeToDelete.size > 1) {
                  if (m.rank == rangeToDelete.start) {
                    List((m.rank + 1).to(rangeToDelete.end))
                  } else if (m.rank == rangeToDelete.end) {
                    List(rangeToDelete.start.to(m.rank - 1))
                  } else {
                    val left  = rangeToDelete.start.to(m.rank - 1)
                    val right = (m.rank + 1).to(rangeToDelete.end)
                    List(left, right)
                  }
                } else {
                  List.empty[NumericRange.Inclusive[Long]]
                }

                ranksRanges -= rangeToDelete
                updatedRanges.foreach { r =>
                  ranksRanges += r
                }
              }
          }
        }
    }

    def createMessagesCache(
        removalListener: RemovalListener[BlockHash, Message]
    ) =
      Sync[F].delay {
        CacheBuilder
          .newBuilder()
          .maximumWeight(maxSizeBytes)
          .weigher((_: BlockHash, msg: Message) => msg.blockSummary.serializedSize) //TODO: Fix the size estimate of a message.
          .removalListener(removalListener)
          .build[BlockHash, Message]()
      }

    for {
      // Should contain only disjoint ranges, represents rank ranges of cached neighbors
      ranksRanges        <- Sync[F].delay(MutableSortedSet.empty[NumericRange.Inclusive[Long]])
      semaphore          <- Semaphore[F](1)
      childrenCache      <- createBlockHashesSetCache
      justificationCache <- createBlockHashesSetCache
      messagesCache      <- createMessagesCache(createMessageRemovalListener(ranksRanges))

      store = new CachingDagStorage[F](
        neighborhoodBefore,
        neighborhoodAfter,
        underlying,
        childrenCache,
        justificationCache,
        messagesCache,
        ranksRanges,
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
