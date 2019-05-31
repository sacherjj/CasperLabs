package io.casperlabs.blockstorage

import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}

import cats._
import cats.effect.{ExitCase, Sync}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.{BlockHash, MeteredBlockStore}
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.configuration.{ignore, relativeToDataDir, SubConfig}
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.Resources.withResource
import io.casperlabs.storage.BlockMsgWithTransform
import org.lmdbjava.DbiFlags.{MDB_CREATE, MDB_DUPSORT}
import org.lmdbjava.Txn.NotReadyException
import org.lmdbjava._

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.language.higherKinds

class LMDBBlockStore[F[_]] private (
    val env: Env[ByteBuffer],
    path: Path,
    blocks: Dbi[ByteBuffer],
    blockSummaryDB: Dbi[ByteBuffer],
    deployHashesDb: Dbi[ByteBuffer]
)(
    implicit
    syncF: Sync[F]
) extends BlockStore[F] {

  implicit class RichBlockHash(byteVector: BlockHash) {

    def toDirectByteBuffer: ByteBuffer = {
      val buffer: ByteBuffer = ByteBuffer.allocateDirect(byteVector.size)
      byteVector.copyTo(buffer)
      // TODO: get rid of this:
      buffer.flip()
      buffer
    }
  }

  private[this] def withTxn[R](txnThunk: => Txn[ByteBuffer])(f: Txn[ByteBuffer] => R): F[R] =
    syncF.bracketCase(syncF.delay(txnThunk)) { txn =>
      syncF.delay {
        val r = f(txn)
        txn.commit()
        r
      }
    } {
      case (txn, ExitCase.Completed) => syncF.delay(txn.close())
      case (txn, _) =>
        syncF.delay {
          try {
            txn.abort()
          } catch {
            case ex: NotReadyException =>
              ex.printStackTrace()
              TxnOps.manuallyAbortTxn(txn)
          }
          txn.close()
        }
    }

  private[this] def withWriteTxn(f: Txn[ByteBuffer] => Unit): F[Unit] =
    withTxn(env.txnWrite())(f)

  private[this] def withReadTxn[R](f: Txn[ByteBuffer] => R): F[R] =
    withTxn(env.txnRead())(f)

  def put(f: => (BlockHash, BlockMsgWithTransform)): F[Unit] =
    withWriteTxn { txn =>
      val (blockHash, blockMsgWithTransform) = f
      blocks.put(
        txn,
        blockHash.toDirectByteBuffer,
        blockMsgWithTransform.toByteString.toDirectByteBuffer
      )
      blockSummaryDB.put(
        txn,
        blockHash.toDirectByteBuffer,
        blockMsgWithTransform.toBlockSummary.toByteString.toDirectByteBuffer
      )
      blockMsgWithTransform.getBlockMessage.getBody.deploys.foreach { d =>
        deployHashesDb.put(
          txn,
          d.getDeploy.deployHash.toDirectByteBuffer,
          blockHash.toDirectByteBuffer
        )
      }
    }

  def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    withReadTxn { txn =>
      Option(blocks.get(txn, blockHash.toDirectByteBuffer))
        .map(r => BlockMsgWithTransform.parseFrom(ByteString.copyFrom(r).newCodedInput()))
    }

  override def findBlockHash(p: BlockHash => Boolean): F[Option[BlockHash]] =
    withReadTxn { txn =>
      withResource(blocks.iterate(txn)) { it =>
        it.asScala.map(kv => ByteString.copyFrom(kv.key)).find(p)
      }
    }

  def getApprovedBlock(): F[Option[ApprovedBlock]] =
    none[ApprovedBlock].pure[F]

  def putApprovedBlock(block: ApprovedBlock): F[Unit] =
    ().pure[F]

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    withReadTxn { txn =>
      Option(blockSummaryDB.get(txn, blockHash.toDirectByteBuffer))
        .map(r => BlockSummary.parseFrom(ByteString.copyFrom(r).newCodedInput()))
    }

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[Unit] = withWriteTxn(blocks.drop)

  override def close(): F[Unit] =
    syncF.delay { env.close() }

  override def findBlockHashesWithDeployhash(deployHash: BlockHash): F[Seq[BlockHash]] =
    withReadTxn { txn =>
      val c = deployHashesDb.iterate(
        txn,
        KeyRange.closed(deployHash.toDirectByteBuffer, deployHash.toDirectByteBuffer)
      )
      c.iterable()
        .asScala
        .map(kv => {
          ByteString.copyFrom(kv.`val`())
        })
        .toSeq
    }
}

object LMDBBlockStore {

  case class Config(
      @ignore
      @relativeToDataDir("lmdb-block-store")
      dir: Path = Paths.get("nonreachable"),
      blockStoreSize: Long,
      maxDbs: Int,
      maxReaders: Int,
      useTls: Boolean
  ) extends SubConfig

  def create[F[_]](config: Config)(
      implicit
      syncF: Sync[F],
      metricsF: Metrics[F]
  ): LMDBBlockStore[F] = {
    if (Files.notExists(config.dir)) Files.createDirectories(config.dir)

    val flags = if (config.useTls) List.empty else List(EnvFlags.MDB_NOTLS)
    val env = Env
      .create()
      .setMapSize(config.blockStoreSize)
      .setMaxDbs(config.maxDbs)
      .setMaxReaders(config.maxReaders)
      .open(config.dir.toFile, flags: _*) //TODO this is a bracket

    val blocks: Dbi[ByteBuffer]         = env.openDbi(s"blocks", MDB_CREATE) //TODO this is a bracket
    val blockSummaryDB: Dbi[ByteBuffer] = env.openDbi(s"blockSummaries", MDB_CREATE)
    val deployHashesDb: Dbi[ByteBuffer] = env.openDbi(s"deployHashes", MDB_CREATE, MDB_DUPSORT)

    new LMDBBlockStore[F](env, config.dir, blocks, blockSummaryDB, deployHashesDb)
    with MeteredBlockStore[F] {
      override implicit val m: Metrics[F] = metricsF
      override implicit val ms: Source    = Metrics.Source(BlockStorageMetricsSource, "lmdb")
      override implicit val a: Apply[F]   = syncF
    }
  }

  def create[F[_]](env: Env[ByteBuffer], path: Path)(
      implicit
      syncF: Sync[F],
      metricsF: Metrics[F]
  ): BlockStore[F] = {
    val blocks: Dbi[ByteBuffer]         = env.openDbi(s"blocks", MDB_CREATE)
    val blockSummaryDb: Dbi[ByteBuffer] = env.openDbi(s"blockSummarise", MDB_CREATE)
    val deployHashesDb: Dbi[ByteBuffer] = env.openDbi(s"deployHashes", MDB_CREATE, MDB_DUPSORT)

    new LMDBBlockStore[F](env, path, blocks, blockSummaryDb, deployHashesDb)
    with MeteredBlockStore[F] {
      override implicit val m: Metrics[F] = metricsF
      override implicit val ms: Source    = Metrics.Source(BlockStorageMetricsSource, "lmdb")
      override implicit val a: Apply[F]   = syncF
    }
  }

  def createWithId(env: Env[ByteBuffer], path: Path): BlockStore[Id] = {
    import io.casperlabs.catscontrib.effect.implicits._
    import io.casperlabs.metrics.Metrics.MetricsNOP
    implicit val metrics: Metrics[Id] = new MetricsNOP[Id]()(syncId)
    LMDBBlockStore.create(env, path)(syncId, metrics)
  }
}
