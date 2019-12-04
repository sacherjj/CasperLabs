package io.casperlabs.storage.block

import cats._
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import com.google.common.cache.{Cache, CacheBuilder, Weigher}
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.block.BlockStorage.{BlockHash, DeployHash, MeteredBlockStorage}
import io.casperlabs.storage.{BlockMsgWithTransform, BlockStorageMetricsSource}

import scala.collection.JavaConverters._

/** Caches recently created blocks so queries that need the full body
  * (e.g. ones that return a deploy, or ones that want block statistics)
  * don't have to hit the disk based storage. It is assumed that users
  * will mostly be interested in the front of the DAG. */
class CachingBlockStorage[F[_]: Sync](
    underlying: BlockStorage[F],
    cache: Cache[BlockHash, BlockMsgWithTransform]
) extends BlockStorage[F] {

  private def cacheOrUnderlying[A](fromCache: => Option[A], fromUnderlying: F[Option[A]]) =
    Sync[F].delay(fromCache) flatMap {
      case None     => fromUnderlying
      case maybeHit => maybeHit.pure[F]
    }

  override def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    cacheOrUnderlying(
      Option(cache.getIfPresent(blockHash)),
      underlying.get(blockHash)
    )

  override def getByPrefix(blockHashPrefix: String): F[Option[BlockMsgWithTransform]] =
    underlying.getByPrefix(blockHashPrefix)

  override def getBlockInfoByPrefix(blockHashPrefix: String): F[Option[BlockInfo]] =
    // Not caching because the DB has clever indexing.
    underlying.getBlockInfoByPrefix(blockHashPrefix)

  override def isEmpty: F[Boolean] = underlying.isEmpty

  override def put(blockHash: BlockHash, blockMsgWithTransform: BlockMsgWithTransform): F[Unit] =
    Sync[F]
      .delay(cache.put(blockHash, blockMsgWithTransform)) *>
      underlying.put(blockHash, blockMsgWithTransform)

  override def contains(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[Boolean] =
    Sync[F]
      .delay(cache.asMap.keySet.contains(blockHash))
      .ifM(true.pure[F], underlying.contains(blockHash))

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    cacheOrUnderlying(
      Option(cache.getIfPresent(blockHash)).map(_.getBlockMessage).map { x =>
        BlockSummary(x.blockHash, x.header, x.signature)
      },
      underlying.getBlockSummary(blockHash)
    )

  override def getBlockInfo(blockHash: BlockHash): F[Option[BlockInfo]] =
    // Not caching because in the future the finality status will get updated.
    underlying.getBlockInfo(blockHash)

  override def findBlockHashesWithDeployHashes(
      deployHashes: List[DeployHash]
  ): F[Map[DeployHash, Set[BlockHash]]] =
    underlying.findBlockHashesWithDeployHashes(deployHashes)

  override def checkpoint(): F[Unit] =
    underlying.checkpoint()

  override def clear(): F[Unit] =
    Sync[F].delay(cache.invalidateAll()) *>
      underlying.clear()

  override def close(): F[Unit] =
    underlying.close()
}

object CachingBlockStorage {
  def apply[F[_]: Sync: Metrics](
      underlying: BlockStorage[F],
      maxSizeBytes: Long,
      name: String = "cache"
  ): F[BlockStorage[F]] = {
    val metricsF = Metrics[F]
    for {
      cache <- Sync[F].delay {
                CacheBuilder
                  .newBuilder()
                  .maximumWeight(maxSizeBytes)
                  .weigher(new Weigher[BlockHash, BlockMsgWithTransform] {
                    def weigh(key: BlockHash, value: BlockMsgWithTransform): Int =
                      value.serializedSize
                  })
                  .build[BlockHash, BlockMsgWithTransform]()
              }
      store = new CachingBlockStorage[F](
        underlying,
        cache
      ) with MeteredBlockStorage[F] {
        override implicit val m: Metrics[F] = metricsF
        override implicit val ms: Metrics.Source =
          Metrics.Source(BlockStorageMetricsSource, name)
        override implicit val a: Apply[F] = Sync[F]
      }
    } yield store
  }
}
