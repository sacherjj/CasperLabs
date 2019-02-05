package io.casperlabs.casper.helper

import java.nio.ByteBuffer
import java.nio.file.{Files, Path}
import java.util.zip.CRC32

import cats.effect.{Concurrent, Sync}
import cats.syntax.functor._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockDagRepresentation.Validator
import io.casperlabs.blockstorage._
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps.RichPath
import monix.eval.Task
import monix.execution.Scheduler
import org.lmdbjava.{Env, EnvFlags}
import org.scalatest.{BeforeAndAfter, Suite}

trait BlockDagStorageFixture extends BeforeAndAfter { self: Suite =>
  val scheduler = Scheduler.fixedPool("block-dag-storage-fixture-scheduler", 4)

  def withStorage[R](f: BlockStore[Task] => IndexedBlockDagStorage[Task] => Task[R]): R = {
    val testProgram = Sync[Task].bracket {
      Sync[Task].delay {
        (BlockDagStorageTestFixture.blockDagStorageDir, BlockDagStorageTestFixture.blockStorageDir)
      }
    } {
      case (blockDagStorageDir, blockStorageDir) =>
        implicit val metrics = new MetricsNOP[Task]()
        implicit val log     = new Log.NOPLog[Task]()
        for {
          blockStore <- BlockDagStorageTestFixture.createBlockStorage[Task](blockStorageDir)
          blockDagStorage <- BlockDagStorageTestFixture.createBlockDagStorage(blockDagStorageDir)(
                              metrics,
                              log,
                              blockStore
                            )
          indexedBlockDagStorage <- IndexedBlockDagStorage.create(blockDagStorage)
          result                 <- f(blockStore)(indexedBlockDagStorage)
        } yield result
    } {
      case (blockDagStorageDir, blockStorageDir) =>
        Sync[Task].delay {
          blockDagStorageDir.recursivelyDelete()
          blockStorageDir.recursivelyDelete()
        }
    }
    testProgram.unsafeRunSync(scheduler)
  }
}

object BlockDagStorageTestFixture {
  def blockDagStorageDir: Path = Files.createTempDirectory("casper-block-dag-storage-test-")
  def blockStorageDir: Path    = Files.createTempDirectory("casper-block-storage-test-")

  def writeInitialLatestMessages(
      latestMessagesData: Path,
      latestMessagesCrc: Path,
      latestMessages: Map[Validator, BlockMessage]
  ): Unit = {
    val data = latestMessages
      .foldLeft(ByteString.EMPTY) {
        case (byteString, (validator, block)) =>
          byteString.concat(validator).concat(block.blockHash)
      }
      .toByteArray
    val crc = new CRC32()
    latestMessages.foreach {
      case (validator, block) =>
        crc.update(validator.concat(block.blockHash).toByteArray)
    }
    val crcByteBuffer = ByteBuffer.allocate(8)
    crcByteBuffer.putLong(crc.getValue)
    Files.write(latestMessagesData, data)
    Files.write(latestMessagesCrc, crcByteBuffer.array())
  }

  def env(
      path: Path,
      mapSize: Long,
      flags: List[EnvFlags] = List(EnvFlags.MDB_NOTLS)
  ): Env[ByteBuffer] =
    Env
      .create()
      .setMapSize(mapSize)
      .setMaxDbs(8)
      .setMaxReaders(126)
      .open(path.toFile, flags: _*)

  val mapSize: Long = 1024L * 1024L * 100L

  def createBlockStorage[F[_]: Concurrent: Metrics: Log](
      blockStorageDir: Path
  ): F[BlockStore[F]] = {
    val env = Context.env(blockStorageDir, mapSize)
    // FIXME: RChain uses this but it doesn't work even if we update HashSetCasperTestNode to use it.
    // FileLMDBIndexBlockStore.create[F](env, blockStorageDir).map(_.right.get)
    Sync[F].delay {
      LMDBBlockStore.create[F](env, blockStorageDir)
    }
  }

  def createBlockDagStorage(blockDagStorageDir: Path)(
      implicit metrics: Metrics[Task],
      log: Log[Task],
      blockStore: BlockStore[Task]
  ): Task[BlockDagStorage[Task]] =
    BlockDagFileStorage.create[Task](
      BlockDagFileStorage.Config(
        blockDagStorageDir.resolve("latest-messages-data"),
        blockDagStorageDir.resolve("latest-messages-crc"),
        blockDagStorageDir.resolve("block-metadata-data"),
        blockDagStorageDir.resolve("block-metadata-crc"),
        blockDagStorageDir.resolve("checkpoints")
      )
    )
}
