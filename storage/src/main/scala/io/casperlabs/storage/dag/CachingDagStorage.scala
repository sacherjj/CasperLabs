package io.casperlabs.storage.dag

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.common.cache.{Cache, CacheBuilder, RemovalListener, RemovalNotification}
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.CachingDagStorage.Rank
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{MeteredDagRepresentation, MeteredDagStorage}

import scala.collection.concurrent.TrieMap

class CachingDagStorage[F[_]: Concurrent](
    // How far to go to the past (by ranks) for caching neighborhood of looked up block
    neighborhoodBefore: Int,
    // How far to go to the future (by ranks) for caching neighborhood of looked up block
    neighborhoodAfter: Int,
    underlying: DagStorage[F] with DagRepresentation[F] with FinalityStorage[F],
    private[dag] val childrenCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val justificationCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val messagesCache: Cache[BlockHash, Message],
    private[dag] val ranksRanges: TrieMap[Rank, Unit],
    semaphore: Semaphore[F]
) extends DagStorage[F]
    with DagRepresentation[F]
    with FinalityStorage[F] {

  /** Unsafe to be invoked concurrently */
  private def unsafeUpdateRanks(start: Rank, end: Rank): F[Unit] = Sync[F].delay {
    ranksRanges ++= (start to end).map((_, ()))
  }

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
    val missingRanks =
      (m.rank - neighborhoodBefore)
        .to(m.rank + neighborhoodAfter)
        .toSet
        .diff(ranksRanges.keySet)
        .toList
    (for {
      start <- missingRanks.minimumOption
      end   <- missingRanks.maximumOption
    } yield topoSort(start, end).compile.toList
      .flatMap(infos => infos.flatten.flatMap(_.summary).traverse_(unsafeCacheSummary)) >> unsafeUpdateRanks(
      start,
      end
    )).getOrElse(().pure[F])
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
  ): fs2.Stream[F, Vector[BlockInfo]] =
    underlying.topoSort(startBlockNumber, endBlockNumber)

  /** Return ranks of blocks in the DAG from a start index to the end. */
  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]] =
    underlying.topoSort(startBlockNumber)

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]] =
    underlying.topoSortTail(tailLength)

  override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
    underlying.latestMessageHash(validator)

  override def latestMessage(validator: Validator): F[Set[Message]] =
    underlying.latestMessage(validator)

  override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
    underlying.latestMessageHashes

  override def latestMessages: F[Map[Validator, Set[Message]]] = underlying.latestMessages

  override def markAsFinalized(
      mainParent: BlockHash,
      secondary: Set[BlockHash]
  ): F[Unit] =
    underlying.markAsFinalized(mainParent, secondary)

  override def isFinalized(block: BlockHash): F[Boolean] =
    underlying.isFinalized(block)

  override def getLastFinalizedBlock: F[BlockHash] = underlying.getLastFinalizedBlock
}

object CachingDagStorage {
  type Rank = Long

  def apply[F[_]: Concurrent: Metrics](
      underlying: DagStorage[F] with DagRepresentation[F] with FinalityStorage[F],
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
      * Otherwise [[CachingDagStorage]] would think that the message is cached even after its eviction. */
    def createMessageRemovalListener(
        ranksRanges: TrieMap[Rank, Unit]
    ): RemovalListener[BlockHash, Message] = { n: RemovalNotification[BlockHash, Message] =>
      Option(n.getValue)
        .foreach { m =>
          ranksRanges -= m.rank
        }
    }

    def createMessagesCache(
        removalListener: RemovalListener[BlockHash, Message]
    ) =
      Sync[F].delay {
        CacheBuilder
          .newBuilder()
          .maximumWeight(maxSizeBytes)
          .weigher((_: BlockHash, msg: Message) => msg.blockSummary.serializedSize)
          .removalListener(removalListener)
          .build[BlockHash, Message]()
      }

    for {
      ranksRanges        <- Sync[F].delay(TrieMap.empty[Rank, Unit])
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
