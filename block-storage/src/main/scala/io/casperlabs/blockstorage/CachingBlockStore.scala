package io.casperlabs.blockstorage

import cats._
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import com.google.common.cache.{Cache, CacheBuilder, Weigher}
import io.casperlabs.blockstorage.BlockStore.{BlockHash, MeteredBlockStore}
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.{Metered, Metrics}
import io.casperlabs.metrics.implicits._
import io.casperlabs.storage.BlockMsgWithTransform

/** Caches recently created blocks so queries that need the full body
  * (e.g. ones that return a deploy, or ones that want block statistics)
  * don't have to hit the disk based storage. It is assumed that users
  * will mostly be interested in the front of the DAG. */
class CachingBlockStore[F[_]: Sync](
    underlying: BlockStore[F],
    cache: Cache[BlockHash, BlockMsgWithTransform]
) extends BlockStore[F] {

  override def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    Sync[F].delay {
      Option(cache.getIfPresent(blockHash))
    } flatMap {
      _.fold(underlying.get(blockHash))(_.some.pure[F])
    }

  override def findBlockHash(p: BlockHash => Boolean): F[Option[BlockHash]] =
    underlying.findBlockHash(p)

  override def put(blockHash: BlockHash, blockMsgWithTransform: BlockMsgWithTransform): F[Unit] =
    Sync[F].delay {
      cache.put(blockHash, blockMsgWithTransform)
    } *>
      underlying.put(blockHash, blockMsgWithTransform)

  override def contains(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[Boolean] =
    underlying.contains(blockHash)

  override def getApprovedBlock(): F[Option[ApprovedBlock]] =
    underlying.getApprovedBlock()

  override def putApprovedBlock(block: ApprovedBlock): F[Unit] =
    underlying.putApprovedBlock(block)

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    underlying.getBlockSummary(blockHash)

  override def findBlockHashesWithDeployhash(deployHash: ByteString): F[Seq[BlockHash]] =
    underlying.findBlockHashesWithDeployhash(deployHash)

  override def checkpoint(): F[Unit] =
    underlying.checkpoint()

  override def clear(): F[Unit] =
    Sync[F].delay(cache.invalidateAll()) *>
      underlying.clear()

  override def close(): F[Unit] =
    underlying.close()
}

object CachingBlockStore {
  def apply[F[_]: Sync: Metrics](
      underlying: BlockStore[F],
      maxSizeBytes: Long,
      name: String = "cache"
  ): F[BlockStore[F]] = {
    val metricsF = Metrics[F]
    for {
      cache <- Sync[F].delay {
                CacheBuilder
                  .newBuilder()
                  .maximumWeight(maxSizeBytes)
                  .weigher(new Weigher[BlockHash, BlockMsgWithTransform] {
                    def weigh(key: BlockHash, value: BlockMsgWithTransform): Int =
                      value.toByteArray.length
                  })
                  .build[BlockHash, BlockMsgWithTransform]()
              }
      store = new CachingBlockStore[F](
        underlying,
        cache
      ) with MeteredBlockStore[F] {
        override implicit val m: Metrics[F] = metricsF
        override implicit val ms: Metrics.Source =
          Metrics.Source(BlockStorageMetricsSource, name)
        override implicit val a: Apply[F] = Sync[F]
      }
    } yield store
  }
}
