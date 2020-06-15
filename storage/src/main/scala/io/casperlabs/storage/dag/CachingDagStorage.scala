package io.casperlabs.storage.dag

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.common.cache.{Cache, CacheBuilder, RemovalListener, RemovalNotification}
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.info.BlockInfo.Status.Finality
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.CachingDagStorage.Rank
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{MeteredDagRepresentation, MeteredDagStorage}
import io.casperlabs.catscontrib.MonadThrowable

import scala.collection.concurrent.TrieMap
import java.util.concurrent.TimeUnit

class CachingDagStorage[F[_]: Concurrent](
    // How far to go to the past (by ranks) for caching neighborhood of looked up block
    neighborhoodBefore: Int,
    // How far to go to the future (by ranks) for caching neighborhood of looked up block
    neighborhoodAfter: Int,
    underlying: DagStorage[F]
      with DagRepresentation[F]
      with FinalityStorage[F]
      with AncestorsStorage[F],
    private[dag] val childrenCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val justificationCache: Cache[BlockHash, Set[BlockHash]],
    private[dag] val messagesCache: Cache[BlockHash, Message],
    private[dag] val finalityCache: Cache[BlockHash, FinalityStorage.FinalityStatus],
    private[dag] val ranksRanges: TrieMap[Rank, Unit],
    semaphore: Semaphore[F]
) extends DagStorage[F]
    with DagRepresentation[F]
    with AncestorsStorage[F]
    with FinalityStorage[F] {

  /** Unsafe to be invoked concurrently */
  private def unsafeUpdateRanks(start: Rank, end: Rank): F[Unit] = Sync[F].delay {
    ranksRanges ++= (start to end).map((_, ()))
  }

  private def cacheOrUnderlying[A](
      fromCache: => Option[A],
      fromUnderlying: F[A],
      intoCache: A => Unit = (_: A) => ()
  ) =
    Sync[F].delay(fromCache) flatMap {
      case None =>
        fromUnderlying map { a =>
          intoCache(a); a
        }
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
      (m.jRank - neighborhoodBefore)
        .to(m.jRank + neighborhoodAfter)
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

  override def getMainChildren(blockHash: BlockHash): F[Set[BlockHash]] =
    underlying.getMainChildren(blockHash)

  /** Return blocks that having a specify justification */
  override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
    cacheOrUnderlying(
      Option(justificationCache.getIfPresent(blockHash)),
      underlying.justificationToBlocks(blockHash)
    )

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]

  override implicit val MT: MonadThrowable[F] = Concurrent[F]

  override private[storage] def findAncestor(block: BlockHash, distance: Long) =
    underlying.findAncestor(block, distance) // TODO(CON-630): Cache

  private[storage] override def insert(block: Block): F[DagRepresentation[F]] =
    for {
      dag     <- underlying.insert(block)
      message <- Sync[F].fromTry(Message.fromBlock(block))
      _ <- semaphore.withPermit {
            unsafeCacheMessage(message) *>
              // Every block is inserted only once. At that point it's fate should be undecided,
              // marked as finalized or orphaned later. We can't assume the same in `unsafeCacheMessage` itself.
              Sync[F].delay(finalityCache.put(block.blockHash, Finality.UNDECIDED))
          }
    } yield dag

  override def checkpoint(): F[Unit] = underlying.checkpoint()

  override def clear(): F[Unit] =
    Sync[F].delay {
      childrenCache.invalidateAll()
      justificationCache.invalidateAll()
      messagesCache.invalidateAll()
      finalityCache.invalidateAll()
      ranksRanges.clear()
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

  override def getBlockInfosByValidator(
      validator: Validator,
      limit: Int,
      lastTimeStamp: Rank,
      lastBlockHash: BlockHash
  ) = underlying.getBlockInfosByValidator(validator, limit, lastTimeStamp, lastBlockHash)

  override def latestGlobal                         = underlying.latestGlobal
  override def latestInEra(keyBlockHash: BlockHash) = underlying.latestInEra(keyBlockHash)

  override def markAsFinalized(
      mainParent: BlockHash,
      secondary: Set[BlockHash],
      orphaned: Set[BlockHash]
  ): F[Unit] =
    for {
      // NOTE: Not caching the `mainParent` as current LFB because every time we start the system
      // the Genesis block is marked as the LFB, which if served back would reset the state.
      _ <- underlying.markAsFinalized(mainParent, secondary, orphaned)
      _ <- Sync[F].delay {
            finalityCache.put(mainParent, Finality.FINALIZED)
            secondary.foreach(finalityCache.put(_, Finality.FINALIZED))
            orphaned.foreach(finalityCache.put(_, Finality.ORPHANED))
          }
    } yield ()

  override def getFinalityStatus(block: BlockHash): F[FinalityStorage.FinalityStatus] =
    cacheOrUnderlying(
      Option(finalityCache.getIfPresent(block)),
      underlying.getFinalityStatus(block),
      finalityCache.put(block, _)
    )

  override def getLastFinalizedBlock: F[BlockHash] =
    underlying.getLastFinalizedBlock
}

object CachingDagStorage {
  type Rank = Long

  def apply[F[_]: Concurrent: Metrics](
      underlying: DagStorage[F]
        with DagRepresentation[F]
        with FinalityStorage[F]
        with AncestorsStorage[F],
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
          ranksRanges -= m.jRank
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

    val createFinalityCache = Sync[F].delay {
      CacheBuilder
        .newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build[BlockHash, Finality]
    }

    for {
      ranksRanges        <- Sync[F].delay(TrieMap.empty[Rank, Unit])
      semaphore          <- Semaphore[F](1)
      childrenCache      <- createBlockHashesSetCache
      justificationCache <- createBlockHashesSetCache
      messagesCache      <- createMessagesCache(createMessageRemovalListener(ranksRanges))
      finalityCache      <- createFinalityCache

      store = new CachingDagStorage[F](
        neighborhoodBefore,
        neighborhoodAfter,
        underlying,
        childrenCache,
        justificationCache,
        messagesCache,
        finalityCache,
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
