package io.casperlabs.blockstorage

import java.nio.ByteBuffer
import java.nio.file.{Files, Path}

import cats._
import cats.effect.{ExitCase, Sync}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.{BlockHash, MeteredBlockStore}
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.Resources.withResource
import org.lmdbjava.DbiFlags.MDB_CREATE
import org.lmdbjava.Txn.NotReadyException
import org.lmdbjava._

import scala.collection.JavaConverters._
import scala.language.higherKinds

class LMDBBlockStore[F[_]] private (val env: Env[ByteBuffer], path: Path, blocks: Dbi[ByteBuffer])(
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

  def put(f: => (BlockHash, BlockMessage)): F[Unit] =
    withWriteTxn { txn =>
      val (blockHash, blockMessage) = f
      blocks.put(
        txn,
        blockHash.toDirectByteBuffer,
        blockMessage.toByteString.toDirectByteBuffer
      )
    }

  def get(blockHash: BlockHash): F[Option[BlockMessage]] =
    withReadTxn { txn =>
      Option(blocks.get(txn, blockHash.toDirectByteBuffer))
        .map(r => BlockMessage.parseFrom(ByteString.copyFrom(r).newCodedInput()))
    }

  override def find(p: BlockHash => Boolean): F[Seq[(BlockHash, BlockMessage)]] =
    withReadTxn { txn =>
      withResource(blocks.iterate(txn)) { iterator =>
        iterator.asScala
          .map(kv => (ByteString.copyFrom(kv.key()), kv.`val`()))
          .withFilter { case (key, _) => p(key) }
          .map {
            case (key, value) =>
              val msg = BlockMessage.parseFrom(ByteString.copyFrom(value).newCodedInput())
              (key, msg)
          }
          .toList
      }
    }

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[Unit] = withWriteTxn(blocks.drop)

  override def close(): F[Unit] =
    syncF.delay { env.close() }
}

object LMDBBlockStore {

  case class Config(
      path: Path,
      mapSize: Long,
      maxDbs: Int = 1,
      maxReaders: Int = 126,
      noTls: Boolean = true
  )

  def create[F[_]](config: Config)(
      implicit
      syncF: Sync[F],
      metricsF: Metrics[F]
  ): LMDBBlockStore[F] = {
    if (Files.notExists(config.path)) Files.createDirectories(config.path)

    val flags = if (config.noTls) List(EnvFlags.MDB_NOTLS) else List.empty
    val env = Env
      .create()
      .setMapSize(config.mapSize)
      .setMaxDbs(config.maxDbs)
      .setMaxReaders(config.maxReaders)
      .open(config.path.toFile, flags: _*) //TODO this is a bracket

    val blocks: Dbi[ByteBuffer] = env.openDbi(s"blocks", MDB_CREATE) //TODO this is a bracket

    new LMDBBlockStore[F](env, config.path, blocks) with MeteredBlockStore[F] {
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
    val blocks: Dbi[ByteBuffer] = env.openDbi(s"blocks", MDB_CREATE)

    new LMDBBlockStore[F](env, path, blocks) with MeteredBlockStore[F] {
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
