package io.casperlabs.blockstorage

import java.io._
import java.nio.ByteBuffer
import java.nio.file._

import cats.{Applicative, Apply, Monad}
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, ExitCase, Resource, Sync}
import cats.implicits._
import cats.mtl.MonadState
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStorage.{BlockHash, MeteredBlockStorage}
import io.casperlabs.shared.Resources.withResource
import io.casperlabs.blockstorage.FileLMDBIndexBlockStorage.Checkpoint
import io.casperlabs.blockstorage.StorageError.StorageErr
import io.casperlabs.blockstorage.util.byteOps._
import io.casperlabs.blockstorage.util.fileIO
import io.casperlabs.blockstorage.util.fileIO.IOError.RaiseIOError
import io.casperlabs.blockstorage.util.fileIO.{IOError, _}
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.catscontrib.MonadStateOps._
import io.casperlabs.metrics.Metrics, Metrics.Source
import io.casperlabs.shared.ByteStringOps._
import io.casperlabs.shared.Log
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.lmdbjava.DbiFlags.{MDB_CREATE, MDB_DUPSORT}
import org.lmdbjava._
import io.casperlabs.shared.PathOps._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import scala.util.matching.Regex

private final case class FileLMDBIndexBlockStorageState[F[_]: Sync](
    blockMessageRandomAccessFile: RandomAccessIO[F],
    checkpoints: Map[Int, Checkpoint],
    currentIndex: Int
)

class FileLMDBIndexBlockStorage[F[_]: Monad: Sync: RaiseIOError: Log] private (
    lock: Semaphore[F],
    env: Env[ByteBuffer],
    index: Dbi[ByteBuffer],
    blockSummaryDB: Dbi[ByteBuffer],
    deployHashesDb: Dbi[ByteBuffer],
    storagePath: Path,
    approvedBlockPath: Path,
    checkpointsDir: Path,
    state: MonadState[F, FileLMDBIndexBlockStorageState[F]]
) extends BlockStorage[F] {
  private case class IndexEntry(checkpointIndex: Int, offset: Long)
  private object IndexEntry {
    def load(byteBuffer: ByteBuffer): IndexEntry = {
      val index  = byteBuffer.getInt()
      val offset = byteBuffer.getLong()
      IndexEntry(index, offset)
    }
  }

  private[this] def withTxn[R](txnThunk: => Txn[ByteBuffer])(f: Txn[ByteBuffer] => R): F[R] =
    Sync[F].bracketCase(Sync[F].delay(txnThunk)) { txn =>
      Sync[F].delay {
        val r = f(txn)
        txn.commit()
        r
      }
    } {
      case (txn, ExitCase.Error(NonFatal(ex))) =>
        val stringWriter = new StringWriter()
        ex.printStackTrace(new PrintWriter(stringWriter))
        Log[F].error(stringWriter.toString) *> Sync[F].delay(txn.close()) *> Sync[F].raiseError(ex)
      case (txn, _) => Sync[F].delay(txn.close())
    }

  private[this] def withWriteTxn(f: Txn[ByteBuffer] => Unit): F[Unit] =
    withTxn(env.txnWrite())(f)

  private[this] def withReadTxn[R](f: Txn[ByteBuffer] => R): F[R] =
    withTxn(env.txnRead())(f)

  private[this] def getBlockMessageRandomAccessFile: F[RandomAccessIO[F]] =
    state.get.map(_.blockMessageRandomAccessFile)
  private[this] def setBlockMessageRandomAccessFile(file: RandomAccessIO[F]): F[Unit] =
    state.modify(_.copy(blockMessageRandomAccessFile = file))
  private[this] def getCheckpoints: F[Map[Int, Checkpoint]] =
    state.get.map(_.checkpoints)
  private[this] def modifyCheckpoints(f: Map[Int, Checkpoint] => Map[Int, Checkpoint]): F[Unit] =
    state.modify(s => s.copy(checkpoints = f(s.checkpoints)))
  private[this] def getCurrentIndex: F[Int] =
    state.get.map(_.currentIndex)
  private[this] def modifyCurrentIndex(f: Int => Int): F[Unit] =
    state.modify(s => s.copy(currentIndex = f(s.currentIndex)))

  private def readBlockMsgWithTransform(indexEntry: IndexEntry): F[BlockMsgWithTransform] = {
    def readBlockMsgWithTransformFromFile(
        storageFile: RandomAccessIO[F]
    ): F[BlockMsgWithTransform] =
      for {
        _                              <- storageFile.seek(indexEntry.offset)
        blockMsgWithTransformSizeOpt   <- storageFile.readInt
        blockMsgWithTransformByteArray = Array.ofDim[Byte](blockMsgWithTransformSizeOpt.get)
        _                              <- storageFile.readFully(blockMsgWithTransformByteArray)
        blockMsgWithTransform          = BlockMsgWithTransform.parseFrom(blockMsgWithTransformByteArray)
      } yield blockMsgWithTransform

    for {
      currentIndex <- getCurrentIndex
      blockMessage <- if (currentIndex == indexEntry.checkpointIndex)
                       for {
                         storageFile           <- getBlockMessageRandomAccessFile
                         blockMsgWithTransform <- readBlockMsgWithTransformFromFile(storageFile)
                       } yield blockMsgWithTransform
                     else
                       for {
                         checkpoints <- getCheckpoints
                         result <- checkpoints.get(indexEntry.checkpointIndex) match {
                                    case Some(checkpoint) =>
                                      Sync[F].bracket {
                                        RandomAccessIO.open[F](
                                          checkpoint.storagePath,
                                          RandomAccessIO.Read
                                        )
                                      }(readBlockMsgWithTransformFromFile)(_.close())
                                    case None =>
                                      RaiseIOError[F].raise[BlockMsgWithTransform](
                                        UnavailableReferencedCheckpoint(
                                          indexEntry.checkpointIndex
                                        )
                                      )
                                  }
                       } yield result
    } yield blockMessage
  }

  override def contains(blockHash: BlockHash)(implicit applicativeF: Applicative[F]): F[Boolean] =
    lock.withPermit(
      for {
        indexEntryOpt <- withReadTxn { txn =>
                          Option(index.get(txn, blockHash.toDirectByteBuffer))
                            .map(IndexEntry.load)
                        }
      } yield indexEntryOpt.isDefined
    )

  override def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]] =
    lock.withPermit(
      for {
        indexEntryOpt <- withReadTxn { txn =>
                          Option(index.get(txn, blockHash.toDirectByteBuffer))
                            .map(IndexEntry.load)
                        }
        result <- indexEntryOpt.traverse(readBlockMsgWithTransform)
      } yield result
    )

  override def findBlockHash(p: BlockHash => Boolean): F[Option[BlockHash]] =
    lock.withPermit(
      withReadTxn { txn =>
        withResource(index.iterate(txn)) { it =>
          it.asScala.map(kv => ByteString.copyFrom(kv.key)).find(p)
        }
      }
    )

  override def put(blockHash: BlockHash, blockMsgWithTransform: BlockMsgWithTransform): F[Unit] =
    lock.withPermit(
      for {
        randomAccessFile               <- getBlockMessageRandomAccessFile
        currentIndex                   <- getCurrentIndex
        endOfFileOffset                <- randomAccessFile.length
        _                              <- randomAccessFile.seek(endOfFileOffset)
        blockMsgWithTransformByteArray = blockMsgWithTransform.toByteArray
        blockSummary                   = blockMsgWithTransform.toBlockSummary
        deployHashes = blockMsgWithTransform.getBlockMessage.getBody.deploys
          .flatMap(_.deploy.map(_.deployHash))
        _ <- randomAccessFile.writeInt(blockMsgWithTransformByteArray.length)
        _ <- randomAccessFile.write(blockMsgWithTransformByteArray)
        _ <- withWriteTxn { txn =>
              val b = blockHash.toDirectByteBuffer
              index.put(
                txn,
                b,
                currentIndex.toByteString.concat(endOfFileOffset.toByteString).toDirectByteBuffer
              )
              blockSummaryDB.put(txn, b, blockSummary.toByteString.toDirectByteBuffer)
              deployHashes.foreach(
                deployHash => deployHashesDb.put(txn, deployHash.toDirectByteBuffer, b)
              )
            }
      } yield ()
    )

  def getApprovedBlock(): F[Option[ApprovedBlock]] =
    lock.withPermit(
      readAllBytesFromFile(approvedBlockPath).map {
        case bytes if bytes.isEmpty =>
          None
        case bytes =>
          Some(ApprovedBlock.parseFrom(bytes))
      }
    )

  def putApprovedBlock(block: ApprovedBlock): F[Unit] =
    lock.withPermit {
      val tmpFile = approvedBlockPath.resolveSibling(approvedBlockPath.getFileName + ".tmp")
      writeToFile(tmpFile, block.toByteArray) >>
        moveFile(tmpFile, approvedBlockPath, StandardCopyOption.ATOMIC_MOVE).as(())
    }

  override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]] =
    withReadTxn { txn =>
      Option(blockSummaryDB.get(txn, blockHash.toDirectByteBuffer))
        .map(r => BlockSummary.parseFrom(ByteString.copyFrom(r).newCodedInput()))
    }

  override def findBlockHashesWithDeployhash(deployHash: ByteString): F[Seq[BlockHash]] =
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

  override def checkpoint(): F[Unit] =
    lock.withPermit(
      for {
        checkpointIndex              <- getCurrentIndex
        checkpointPath               = checkpointsDir.resolve(checkpointIndex.toString)
        blockMessageRandomAccessFile <- getBlockMessageRandomAccessFile
        _                            <- blockMessageRandomAccessFile.close
        _                            <- moveFile(storagePath, checkpointPath, StandardCopyOption.ATOMIC_MOVE)
        newBlockMessageRandomAccessFile <- RandomAccessIO
                                            .open[F](storagePath, RandomAccessIO.ReadWrite)
        _ <- setBlockMessageRandomAccessFile(newBlockMessageRandomAccessFile)
        _ <- modifyCheckpoints(
              _.updated(checkpointIndex, Checkpoint(checkpointIndex, checkpointPath))
            )
        _ <- modifyCurrentIndex(_ + 1)
      } yield ()
    )

  override def clear(): F[Unit] =
    lock.withPermit(
      for {
        blockMessageRandomAccessFile <- getBlockMessageRandomAccessFile
        _ <- withWriteTxn { txn =>
              index.drop(txn)
            }
        _      <- checkpointsDir.deleteDirectory()
        _      <- approvedBlockPath.deleteSingleFile()
        _      <- makeDirectory(checkpointsDir)
        _      <- modifyCheckpoints(_.empty)
        _      <- modifyCurrentIndex(_ => 0)
        result <- blockMessageRandomAccessFile.setLength(0)
      } yield result
    )

  override def close(): F[Unit] =
    lock.withPermit(
      for {
        blockMessageRandomAccessFile <- getBlockMessageRandomAccessFile
        _                            <- blockMessageRandomAccessFile.close
        envCloseResult               <- Sync[F].delay[Unit] { env.close() }.attempt
        _ <- envCloseResult match {
              case Right(_) => ().pure[F]
              case Left(e: IOException) =>
                RaiseIOError[F].raise[Unit](ClosingFailed(e))
              case Left(t) =>
                RaiseIOError[F].raise[Unit](UnexpectedIOError(t))
            }
      } yield ()
    )
}

object FileLMDBIndexBlockStorage {
  private val checkpointPattern: Regex = "([0-9]+)".r

  final case class Config(
      storagePath: Path,
      indexPath: Path,
      approvedBlockPath: Path,
      checkpointsDirPath: Path,
      mapSize: Long,
      maxDbs: Int = 1,
      maxReaders: Int = 126,
      noTls: Boolean = true
  )

  private[blockstorage] final case class CheckpointIndex(
      env: Env[ByteBuffer],
      index: Dbi[ByteBuffer]
  )

  private[blockstorage] final case class Checkpoint(
      index: Int,
      storagePath: Path
  )

  private def loadCheckpoints[F[_]: Sync: Log: RaiseIOError](
      checkpointsDirPath: Path
  ): F[StorageErr[List[Checkpoint]]] =
    for {
      _                   <- makeDirectory(checkpointsDirPath)
      checkpointFilesList <- listRegularFiles(checkpointsDirPath)
      checkpoints <- checkpointFilesList.flatTraverse { filePath =>
                      filePath.getFileName.toString match {
                        case checkpointPattern(index) =>
                          List(Checkpoint(index.toInt, filePath)).pure[F]
                        case other =>
                          Log[F]
                            .warn(s"Ignoring directory '$other': not a valid checkpoint name") *>
                            List.empty[Checkpoint].pure[F]
                      }
                    }
      sortedCheckpoints = checkpoints.sortBy(_.index)
      result = if (sortedCheckpoints.headOption.forall(_.index == 0)) {
        if (sortedCheckpoints.isEmpty ||
            sortedCheckpoints.zip(sortedCheckpoints.tail).forall {
              case (current, next) => current.index + 1 == next.index
            }) {
          sortedCheckpoints.asRight[StorageError]
        } else {
          CheckpointsAreNotConsecutive(sortedCheckpoints.map(_.storagePath))
            .asLeft[List[Checkpoint]]
        }
      } else {
        CheckpointsDoNotStartFromZero(sortedCheckpoints.map(_.storagePath))
          .asLeft[List[Checkpoint]]
      }
    } yield result

  def create[F[_]: Concurrent: Log: Metrics](
      env: Env[ByteBuffer],
      blockStorageDataDir: Path
  ): F[StorageErr[BlockStorage[F]]] =
    create(
      env,
      blockStorageDataDir.resolve("storage"),
      blockStorageDataDir.resolve("approved-block"),
      blockStorageDataDir.resolve("checkpoints")
    )

  def create[F[_]: Monad: Concurrent: Log: Metrics](
      env: Env[ByteBuffer],
      storagePath: Path,
      approvedBlockPath: Path,
      checkpointsDirPath: Path
  ): F[StorageErr[BlockStorage[F]]] = {
    implicit val raiseIOError: RaiseIOError[F] = IOError.raiseIOErrorThroughSync[F]
    for {
      lock <- Semaphore[F](1)
      index <- Sync[F].delay {
                env.openDbi(s"block_storage_index", MDB_CREATE)
              }
      blockSummaryDB <- Sync[F].delay {
                         env.openDbi("blockSummaries", MDB_CREATE)
                       }
      deployHashesDB <- Sync[F].delay {
                         env.openDbi("deployHashes", MDB_CREATE, MDB_DUPSORT)
                       }
      _                            <- createNewFile(approvedBlockPath)
      blockMessageRandomAccessFile <- RandomAccessIO.open(storagePath, RandomAccessIO.ReadWrite)
      sortedCheckpointsEither      <- loadCheckpoints(checkpointsDirPath)
      result <- sortedCheckpointsEither match {
                 case Right(sortedCheckpoints) =>
                   val checkpointsMap = sortedCheckpoints.map(c => c.index -> c).toMap
                   val currentIndex   = sortedCheckpoints.lastOption.map(_.index + 1).getOrElse(0)
                   val initialState = FileLMDBIndexBlockStorageState[F](
                     blockMessageRandomAccessFile,
                     checkpointsMap,
                     currentIndex
                   )

                   initialState.useStateByRef[F] { st =>
                     val metricsF = Metrics[F]
                     val store: BlockStorage[F] =
                       new FileLMDBIndexBlockStorage[F](
                         lock,
                         env,
                         index,
                         blockSummaryDB,
                         deployHashesDB,
                         storagePath,
                         approvedBlockPath,
                         checkpointsDirPath,
                         st
                       ) with MeteredBlockStorage[F] {
                         override implicit val m: Metrics[F] = metricsF
                         override implicit val ms: Source =
                           Metrics.Source(BlockStorageMetricsSource, "file-lmdb")
                         override implicit val a: Apply[F] = Concurrent[F]
                       }
                     store.asRight[StorageError]
                   }
                 case Left(e) => e.asLeft[BlockStorage[F]].pure[F]
               }
    } yield result
  }

  def create[F[_]: Monad: Concurrent: Log: Metrics](
      config: Config
  ): F[StorageErr[BlockStorage[F]]] =
    for {
      notExists <- Sync[F].delay(Files.notExists(config.indexPath))
      _         <- Sync[F].delay(Files.createDirectories(config.indexPath)).whenA(notExists)
      env <- Sync[F].delay {
              val flags = if (config.noTls) List(EnvFlags.MDB_NOTLS) else List.empty
              Env
                .create()
                .setMapSize(config.mapSize)
                .setMaxDbs(config.maxDbs)
                .setMaxReaders(config.maxReaders)
                .open(config.indexPath.toFile, flags: _*)
            }
      result <- create[F](
                 env,
                 config.storagePath,
                 config.approvedBlockPath,
                 config.checkpointsDirPath
               )
    } yield result

  def apply[F[_]: Concurrent: Log: RaiseIOError: Metrics](
      dataDir: Path,
      blockStoragePath: Path,
      mapSize: Long
  ): Resource[F, BlockStorage[F]] =
    Resource.make {
      for {
        _               <- fileIO.makeDirectory(dataDir)
        _               <- fileIO.makeDirectory(blockStoragePath)
        blockStorageEnv = Context.env(blockStoragePath, mapSize)
        storage <- FileLMDBIndexBlockStorage
                    .create(blockStorageEnv, blockStoragePath)
                    .map(_.right.get)
      } yield storage
    }(_.close())
}
