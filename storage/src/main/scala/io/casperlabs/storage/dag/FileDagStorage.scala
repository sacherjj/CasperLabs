package io.casperlabs.storage.dag

import java.nio.file.{Path, Paths, StandardCopyOption}
import java.nio.{BufferUnderflowException, ByteBuffer}

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import cats.mtl.MonadState
import cats.{Apply, Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.catscontrib.MonadStateOps._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.configuration.{ignore, relativeToDataDir, SubConfig}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.{Log, LogSource}
import io.casperlabs.storage._
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.MeteredDagStorage
import io.casperlabs.storage.dag.FileDagStorage.Checkpoint
import io.casperlabs.storage.util.byteOps._
import io.casperlabs.storage.util.fileIO.IOError.RaiseIOError
import io.casperlabs.storage.util.fileIO.{IOError, _}
import io.casperlabs.storage.util.{fileIO, Crc32, TopologicalSortUtil}

import scala.ref.WeakReference
import scala.util.matching.Regex

private final case class FileDagStorageState[F[_]: Sync](
    latestMessages: Map[Validator, BlockHash],
    childMap: Map[BlockHash, Set[BlockHash]],
    justificationMap: Map[BlockHash, Set[BlockHash]],
    dataLookup: Map[BlockHash, BlockSummary],
    // Top layers of the DAG, rank by rank.
    topoSort: Vector[Vector[BlockHash]],
    // The rank of the blocks in `topoSort.head`. Everything before it is in checkpoints.
    sortOffset: Long,
    checkpoints: List[Checkpoint],
    latestMessagesLogOutputStream: FileOutputStreamIO[F],
    latestMessagesLogSize: Int,
    latestMessagesCrc: Crc32[F],
    blockSummaryLogOutputStream: FileOutputStreamIO[F],
    blockSummaryCrc: Crc32[F]
)

class FileDagStorage[F[_]: Concurrent: Log: BlockStorage: RaiseIOError] private (
    lock: Semaphore[F],
    latestMessagesDataFilePath: Path,
    latestMessagesCrcFilePath: Path,
    latestMessagesLogMaxSizeFactor: Int,
    blockSummaryLogPath: Path,
    blockSummaryCrcPath: Path,
    state: MonadState[F, FileDagStorageState[F]]
) extends DagStorage[F] {
  import FileDagStorage._

  private implicit val logSource = LogSource(FileDagStorage.getClass)

  private case class FileDagRepresentation(
      latestMessagesMap: Map[Validator, BlockHash],
      childMap: Map[BlockHash, Set[BlockHash]],
      justificationMap: Map[BlockHash, Set[BlockHash]],
      dataLookup: Map[BlockHash, BlockSummary],
      topoSortVector: Vector[Vector[BlockHash]],
      sortOffset: Long
  ) extends DagRepresentation[F] {

    // Number of the last rank in topoSortVector
    def sortEndBlockNumber = sortOffset + topoSortVector.size - 1

    def children(blockHash: BlockHash): F[Set[BlockHash]] =
      childMap.get(blockHash) match {
        case Some(children) =>
          children.pure[F]
        case None =>
          for {
            blockOpt <- BlockStorage[F].getBlockMessage(blockHash)
            result <- blockOpt match {
                       case Some(block) =>
                         val number = block.getHeader.rank
                         if (number >= sortOffset) {
                           Set.empty[BlockHash].pure[F]
                         } else {
                           lock.withPermit(
                             loadCheckpoint(number).map(
                               _.flatMap(_.childMap.get(blockHash))
                                 .getOrElse(Set.empty)
                             )
                           )
                         }
                       case None => Set.empty[BlockHash].pure[F]
                     }
          } yield result
      }

    def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
      justificationMap.get(blockHash) match {
        case Some(blocks) =>
          blocks.pure[F]
        case None =>
          for {
            blockOpt <- BlockStorage[F].getBlockMessage(blockHash)
            result <- blockOpt match {
                       case Some(block) =>
                         val number = block.getHeader.rank
                         if (number >= sortOffset) {
                           Set.empty[BlockHash].pure[F]
                         } else {
                           lock.withPermit(
                             loadCheckpoint(number).map(
                               _.flatMap(_.justificationMap.get(blockHash))
                                 .getOrElse(Set.empty)
                             )
                           )
                         }
                       case None => Set.empty[BlockHash].pure[F]
                     }
          } yield result
      }

    def lookup(blockHash: BlockHash): F[Option[BlockSummary]] =
      dataLookup
        .get(blockHash)
        .fold(
          BlockStorage[F].getBlockMessage(blockHash).map(_.map(BlockSummary.fromBlock))
        )(blockSummary => Option(blockSummary).pure[F])

    def contains(blockHash: BlockHash): F[Boolean] =
      dataLookup.get(blockHash).fold(BlockStorage[F].contains(blockHash))(_ => true.pure[F])

    def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]] =
      topoSort(startBlockNumber, sortEndBlockNumber)

    def topoSort(startBlockNumber: Long, endBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]] = {
      val length = endBlockNumber - startBlockNumber + 1
      val res: F[Vector[Vector[BlockHash]]] = if (length > Int.MaxValue) { // Max Vector length
        Sync[F].raiseError(TopoSortLengthIsTooBig(length))
      } else if (startBlockNumber >= sortOffset) {
        val offset = startBlockNumber - sortOffset
        assertCond[F]("Topo sort offset is not a valid Int", offset.isValidInt) >>
          topoSortVector.slice(offset.toInt, (offset + length).toInt).pure[F]
      } else {
        lock.withPermit(
          for {
            checkpoints          <- (state >> 'checkpoints).get
            checkpointsWithIndex = checkpoints.zipWithIndex
            checkpointsToLoad = checkpointsWithIndex.filter {
              case (checkpoint, _) =>
                startBlockNumber <= checkpoint.end &&
                  endBlockNumber >= checkpoint.start
            }
            checkpointsDagInfos <- checkpointsToLoad.traverse {
                                    case (startingCheckpoint, index) =>
                                      loadCheckpointDagInfo(startingCheckpoint, index)
                                  }
            // Load previous ranks from the checkpoints.
            topoSortPrefix = checkpointsDagInfos.toVector.flatMap { checkpointsDagInfo =>
              val offset = startBlockNumber - checkpointsDagInfo.sortOffset
              // offset is always a valid Int since the method result's length was validated before
              checkpointsDagInfo.topoSort.drop(offset.toInt) // negative drops are ignored
            }
            result = if (topoSortPrefix.length >= length) topoSortPrefix
            else (topoSortPrefix ++ topoSortVector).take(length.toInt)
          } yield result
        )
      }
      fs2.Stream.eval(res).flatMap(v => fs2.Stream.emits(v))
    }

    def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockHash]] = {
      val endBlockNumber   = sortEndBlockNumber
      val startBlockNumber = Math.max(0L, endBlockNumber - tailLength + 1)
      topoSort(startBlockNumber, endBlockNumber)
    }

    def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
      latestMessagesMap.get(validator).pure[F]

    def latestMessage(validator: Validator): F[Option[BlockSummary]] =
      latestMessagesMap.get(validator).flatTraverse(lookup)

    def latestMessageHashes: F[Map[Validator, BlockHash]] =
      latestMessagesMap.pure[F]

    def latestMessages: F[Map[Validator, BlockSummary]] =
      latestMessagesMap.toList
        .traverse {
          case (validator, hash) => lookup(hash).map(validator -> _.get)
        }
        .map(_.toMap)
  }

  private def loadDagInfo(checkpoint: Checkpoint): F[CheckpointedDagInfo] = {
    val checkpointDataInputResource = Resource.make(
      RandomAccessIO.open[F](checkpoint.path, RandomAccessIO.Read)
    )(_.close)
    for {
      blockSummaryList             <- checkpointDataInputResource.use(readDataLookupData[F])
      dataLookup                   = blockSummaryList.toMap
      childAndJustificationMap     = extractChildAndJustificationMap(blockSummaryList)
      (childMap, justificationMap) = childAndJustificationMap
      topoSort                     <- extractTopoSort[F](blockSummaryList)
    } yield CheckpointedDagInfo(childMap, justificationMap, dataLookup, topoSort, checkpoint.start)
  }

  private def loadCheckpointDagInfo(checkpoint: Checkpoint, index: Int): F[CheckpointedDagInfo] =
    checkpoint.dagInfo.flatMap(_.get) match {
      case Some(dagInfo) =>
        dagInfo.pure[F]
      case None =>
        for {
          loadedDagInfo <- loadDagInfo(checkpoint)
          newCheckpoint = checkpoint.copy(dagInfo = Some(WeakReference(loadedDagInfo)))
          _             <- (state >> 'checkpoints).modify(_.patch(index, List(newCheckpoint), 1))
        } yield loadedDagInfo
    }

  private def loadCheckpoint(offset: Long): F[Option[CheckpointedDagInfo]] =
    for {
      checkpoints <- (state >> 'checkpoints).get
      neededCheckpoint = checkpoints.zipWithIndex.find {
        case (c, _) => c.start <= offset && offset < c.end
      }
      result <- neededCheckpoint match {
                 case None =>
                   Log[F].warn(
                     s"Requested a block with block number $offset, but there is no checkpoint for it"
                   ) *> None.pure[F]
                 case Some((checkpoint, i)) =>
                   loadCheckpointDagInfo(checkpoint, i).map(Option(_))
               }
    } yield result

  private def updateLatestMessagesFile(validators: List[Validator], blockHash: BlockHash): F[Unit] =
    for {
      latestMessagesCrc <- (state >> 'latestMessagesCrc).get
      _ <- validators.traverse_ { validator =>
            val toAppend = validator.concat(blockHash).toByteArray
            for {
              latestMessagesLogOutputStream <- (state >> 'latestMessagesLogOutputStream).get
              _                             <- latestMessagesLogOutputStream.write(toAppend)
              _                             <- latestMessagesLogOutputStream.flush
              _ <- latestMessagesCrc.update(toAppend).flatMap { _ =>
                    updateLatestMessagesCrcFile(latestMessagesCrc)
                  }
            } yield ()
          }
      _ <- (state >> 'latestMessagesLogSize).modify(_ + 1)
    } yield ()

  private def updateLatestMessagesCrcFile(newCrc: Crc32[F]): F[Unit] =
    for {
      newCrcBytes <- newCrc.bytes
      tmpCrc      <- createSameDirectoryTemporaryFile(latestMessagesCrcFilePath)
      _           <- writeToFile[F](tmpCrc, newCrcBytes)
      _           <- replaceFile(tmpCrc, latestMessagesCrcFilePath)
    } yield ()

  private def replaceFile(from: Path, to: Path): F[Path] =
    moveFile[F](from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  private def squashLatestMessagesDataFile(): F[Unit] =
    for {
      latestMessages                <- (state >> 'latestMessages).get
      latestMessagesLogOutputStream <- (state >> 'latestMessagesLogOutputStream).get
      _                             <- latestMessagesLogOutputStream.close
      tmpSquashedData               <- createSameDirectoryTemporaryFile(latestMessagesDataFilePath)
      tmpSquashedCrc                <- createSameDirectoryTemporaryFile(latestMessagesCrcFilePath)
      dataByteBuffer                = ByteBuffer.allocate(64 * latestMessages.size)
      _ <- latestMessages.toList.traverse_ {
            case (validator, blockHash) =>
              Sync[F].delay {
                dataByteBuffer.put(validator.toByteArray)
                dataByteBuffer.put(blockHash.toByteArray)
              }
          }
      _                <- writeToFile[F](tmpSquashedData, dataByteBuffer.array())
      squashedCrc      <- Crc32.emptyF[F]()
      _                <- squashedCrc.update(dataByteBuffer.array())
      squashedCrcBytes <- squashedCrc.bytes
      _                <- writeToFile[F](tmpSquashedCrc, squashedCrcBytes)
      _                <- replaceFile(tmpSquashedData, latestMessagesDataFilePath)
      _                <- replaceFile(tmpSquashedCrc, latestMessagesCrcFilePath)
      newLatestMessagesLogOutputStream <- FileOutputStreamIO
                                           .open[F](latestMessagesDataFilePath, true)
      _ <- (state >> 'latestMessagesLogOutputStream).set(newLatestMessagesLogOutputStream)
      _ <- (state >> 'latestMessagesCrc).set(squashedCrc)
      _ <- (state >> 'latestMessagesLogSize).set(0)
    } yield ()

  private def squashLatestMessagesDataFileIfNeeded(): F[Unit] =
    for {
      latestMessages        <- (state >> 'latestMessages).get
      latestMessagesLogSize <- (state >> 'latestMessagesLogSize).get
      result <- if (latestMessagesLogSize > latestMessages.size * latestMessagesLogMaxSizeFactor) {
                 squashLatestMessagesDataFile()
               } else {
                 ().pure[F]
               }
    } yield result

  private def updateDataLookupFile(blockSummary: BlockSummary): F[Unit] =
    for {
      dataLookupCrc          <- (state >> 'blockSummaryCrc).get
      blockBytes             = blockSummary.toByteString
      toAppend               = blockBytes.size.toByteString.concat(blockBytes).toByteArray
      dataLookupOutputStream <- (state >> 'blockSummaryLogOutputStream).get
      _                      <- dataLookupOutputStream.write(toAppend)
      _                      <- dataLookupOutputStream.flush
      _                      <- dataLookupCrc.update(toAppend)
      _                      <- updateDataLookupCrcFile(dataLookupCrc)
    } yield ()

  private def updateDataLookupCrcFile(newCrc: Crc32[F]): F[Unit] =
    for {
      newCrcBytes <- newCrc.bytes
      tmpCrc      <- createSameDirectoryTemporaryFile(blockSummaryCrcPath)
      _           <- writeToFile[F](tmpCrc, newCrcBytes)
      _           <- replaceFile(tmpCrc, blockSummaryCrcPath)
    } yield ()

  private def representation: F[DagRepresentation[F]] =
    (
      (state >> 'latestMessages).get,
      (state >> 'childMap).get,
      (state >> 'justificationMap).get,
      (state >> 'dataLookup).get,
      (state >> 'topoSort).get,
      (state >> 'sortOffset).get
    ) mapN FileDagRepresentation.apply

  def getRepresentation: F[DagRepresentation[F]] =
    lock.withPermit(representation)

  def insert(block: Block): F[DagRepresentation[F]] =
    lock.withPermit(
      (state >> 'dataLookup).get
        .map(_.contains(block.blockHash))
        .ifM(
          Log[F].warn(s"Block ${Base16.encode(block.blockHash.toByteArray)} is already stored"),
          insertBlock(block)
        ) *> representation
    )

  private def insertBlock(block: Block) =
    for {
      _            <- squashLatestMessagesDataFileIfNeeded()
      blockSummary = BlockSummary.fromBlock(block)
      _ <- assertCond[F](
            "Cannot insert block: blockHash.size != 32",
            block.blockHash.size == 32
          )
      _ <- (state >> 'dataLookup).modify(_.updated(block.blockHash, blockSummary))
      _ <- (state >> 'childMap).modify(
            block.parents
              .foldLeft(_) {
                case (acc, p) =>
                  val currChildren = acc.getOrElse(p, Set.empty[BlockHash])
                  acc.updated(p, currChildren + block.blockHash)
              }
              .updated(block.blockHash, Set.empty[BlockHash])
          )
      _ <- (state >> 'justificationMap).modify(
            block.getHeader.justifications
              .foldLeft(_) {
                case (acc, justification) =>
                  val currBlocks =
                    acc.getOrElse(justification.latestBlockHash, Set.empty[BlockHash])
                  acc.updated(justification.latestBlockHash, currBlocks + block.blockHash)
              }
              .updated(block.blockHash, Set.empty[BlockHash])
          )
      _ <- (state >> 'topoSort).modify(
            TopologicalSortUtil.update(_, 0L, block)
          )
      //Block which contains newly bonded validators will not
      //have those validators in its justification
      newValidators = block.getHeader.getState.bonds
        .map(_.validatorPublicKey)
        .toSet
        .diff(block.getHeader.justifications.map(_.validatorPublicKey).toSet)
      validator = block.getHeader.validatorPublicKey
      _ <- Log[F]
            .warn(
              s"Block ${Base16.encode(block.blockHash.toByteArray)} validator is empty"
            )
            .whenA(validator.isEmpty)
      _ <- Sync[F]
            .raiseError[Set[ByteString]](BlockValidatorIsMalformed(block))
            .whenA(!validator.isEmpty && validator.size != 32)
      toUpdateValidators = if (validator.isEmpty) {
        // For Genesis, all validators are "new".
        newValidators
      } else {
        // For any other block, only validator that produced it
        // needs to have its "latest message" updated.
        List(validator)
      }
      _ <- (state >> 'latestMessages).modify {
            toUpdateValidators.foldLeft(_) {
              //Update new validators with block in which
              //they were bonded (i.e. this block)
              case (acc, v) => acc.updated(v, block.blockHash)
            }
          }
      _ <- updateLatestMessagesFile(
            toUpdateValidators.toList,
            block.blockHash
          )
      _ <- updateDataLookupFile(blockSummary)
    } yield ()

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[Unit] =
    lock.withPermit(
      for {
        latestMessagesLogOutputStream <- (state >> 'latestMessagesLogOutputStream).get
        _                             <- latestMessagesLogOutputStream.close
        blockSummaryLogOutputStream   <- (state >> 'blockSummaryLogOutputStream).get
        _                             <- blockSummaryLogOutputStream.close
        _                             <- writeToFile(latestMessagesDataFilePath, Array.emptyByteArray)
        _                             <- writeToFile(blockSummaryLogPath, Array.emptyByteArray)
        newLatestMessagesCrc          <- Crc32.emptyF[F]()
        newLatestMessagesCrcBytes     <- newLatestMessagesCrc.bytes
        _                             <- writeToFile(latestMessagesCrcFilePath, newLatestMessagesCrcBytes)
        newBlockSummaryCrc            <- Crc32.emptyF[F]()
        newBlockSummaryCrcBytes       <- newBlockSummaryCrc.bytes
        _                             <- writeToFile(blockSummaryCrcPath, newBlockSummaryCrcBytes)
        _                             <- (state >> 'dataLookup).set(Map.empty)
        _                             <- (state >> 'childMap).set(Map.empty)
        _                             <- (state >> 'justificationMap).set(Map.empty)
        _                             <- (state >> 'topoSort).set(Vector.empty)
        _                             <- (state >> 'latestMessages).set(Map.empty)
        newLatestMessagesLogOutputStream <- FileOutputStreamIO
                                             .open[F](latestMessagesDataFilePath, true)
        _                              <- (state >> 'latestMessagesLogOutputStream).set(newLatestMessagesLogOutputStream)
        newBlockSummaryLogOutputStream <- FileOutputStreamIO.open[F](blockSummaryLogPath, true)
        _                              <- (state >> 'blockSummaryLogOutputStream).set(newBlockSummaryLogOutputStream)
        _                              <- (state >> 'latestMessagesLogSize).set(0)
        _                              <- (state >> 'latestMessagesCrc).set(newLatestMessagesCrc)
        _                              <- (state >> 'blockSummaryCrc).set(newBlockSummaryCrc)
      } yield ()
    )

  def close(): F[Unit] =
    lock.withPermit(
      for {
        latestMessagesLogOutputStream <- (state >> 'latestMessagesLogOutputStream).get
        _                             <- latestMessagesLogOutputStream.close
        blockSummaryLogOutputStream   <- (state >> 'blockSummaryLogOutputStream).get
        _                             <- blockSummaryLogOutputStream.close
      } yield ()
    )
}

object FileDagStorage {
  private implicit val logSource       = LogSource(FileDagStorage.getClass)
  private val checkpointPattern: Regex = "([0-9]+)-([0-9]+)".r
  type ChildrenMapAndJustificationMap =
    (Map[BlockHash, Set[BlockHash]], Map[BlockHash, Set[BlockHash]])

  final case class Config(
      @ignore
      @relativeToDataDir("dag-file-storage")
      dir: Path = Paths.get("nonreachable"),
      latestMessagesLogMaxSizeFactor: Int = 10
  ) extends SubConfig {
    val latestMessagesLogPath: Path = dir.resolve("latest-messages-log")
    val latestMessagesCrcPath: Path = dir.resolve("latest-messages-crc")
    val blockSummaryLogPath: Path   = dir.resolve("block-summary-log")
    val blockSummaryCrcPath: Path   = dir.resolve("block-summary-crc")
    val checkpointsDirPath: Path    = dir.resolve("checkpoints")
  }

  def assertCond[F[_]: MonadError[?[_], Throwable]](errMsg: String, b: => Boolean): F[Unit] =
    MonadError[F, Throwable].raiseError(new IllegalArgumentException(errMsg)).whenA(!b)

  private[storage] final case class CheckpointedDagInfo(
      childMap: Map[BlockHash, Set[BlockHash]],
      justificationMap: Map[BlockHash, Set[BlockHash]],
      dataLookup: Map[BlockHash, BlockSummary],
      topoSort: Vector[Vector[BlockHash]],
      sortOffset: Long
  )

  private[storage] final case class Checkpoint(
      start: Long,
      end: Long,
      path: Path,
      dagInfo: Option[WeakReference[CheckpointedDagInfo]]
  )

  private def readCrc[F[_]: Sync: Log: RaiseIOError](crcPath: Path): F[Long] =
    for {
      _          <- createNewFile[F](crcPath)
      bytes      <- readAllBytesFromFile[F](crcPath)
      byteBuffer = ByteBuffer.wrap(bytes)
      result <- Sync[F].delay { byteBuffer.getLong() }.handleErrorWith {
                 case _: BufferUnderflowException =>
                   for {
                     _ <- Log[F].warn(s"CRC file $crcPath did not contain a valid CRC value")
                   } yield 0
                 case exception =>
                   Sync[F].raiseError(exception)
               }
    } yield result

  private def calculateLatestMessagesCrc[F[_]: Monad](
      latestMessagesList: List[(Validator, BlockHash)]
  ): Crc32[F] =
    Crc32[F](
      latestMessagesList
        .foldLeft(ByteString.EMPTY) {
          case (byteString, (validator, blockHash)) =>
            byteString.concat(validator.concat(blockHash))
        }
        .toByteArray
    )

  private def readLatestMessagesData[F[_]: Sync: Log](
      randomAccessIO: RandomAccessIO[F]
  ): F[(List[(Validator, BlockHash)], Int)] = {
    def readRec(
        result: List[(Validator, BlockHash)],
        logSize: Int
    ): F[(List[(Validator, BlockHash)], Int)] = {
      val validatorPk = Array.fill[Byte](32)(0)
      val blockHash   = Array.fill[Byte](32)(0)
      for {
        validatorPkRead <- randomAccessIO.readFully(validatorPk)
        blockHashRead   <- randomAccessIO.readFully(blockHash)
        result <- (validatorPkRead, blockHashRead) match {
                   case (Some(_), Some(_)) =>
                     val pair = (ByteString.copyFrom(validatorPk), ByteString.copyFrom(blockHash))
                     readRec(
                       pair :: result,
                       logSize + 1
                     )
                   case (None, None) =>
                     (result.reverse, logSize).pure[F]
                   case (_, _) =>
                     for {
                       _ <- Log[F].error("Latest messages log is malformed")
                       result <- Sync[F].raiseError[(List[(Validator, BlockHash)], Int)](
                                  LatestMessagesLogIsMalformed
                                )
                     } yield result
                 }
      } yield result
    }
    readRec(List.empty, 0)
  }

  private def validateLatestMessagesData[F[_]: Monad](
      latestMessagesRaf: RandomAccessIO[F],
      readLatestMessagesCrc: Long,
      latestMessagesList: List[(Validator, BlockHash)]
  ): F[(Map[Validator, BlockHash], Crc32[F])] = {
    val fullCalculatedCrc = calculateLatestMessagesCrc[F](latestMessagesList)
    fullCalculatedCrc.value.flatMap { fullCalculatedCrcValue =>
      if (fullCalculatedCrcValue == readLatestMessagesCrc) {
        (latestMessagesList.toMap, fullCalculatedCrc).pure[F]
      } else {
        val withoutLastCalculatedCrc = calculateLatestMessagesCrc[F](latestMessagesList.init)
        withoutLastCalculatedCrc.value.flatMap { withoutLastCalculatedCrcValue =>
          if (withoutLastCalculatedCrcValue == readLatestMessagesCrc) {
            for {
              length <- latestMessagesRaf.length
              _      <- latestMessagesRaf.setLength(length - 64)
            } yield (latestMessagesList.init.toMap, withoutLastCalculatedCrc)
          } else {
            // TODO: Restore latest messages from the persisted DAG
            latestMessagesRaf.setLength(0)
            (Map.empty[Validator, BlockHash], Crc32.empty[F]()).pure[F]
          }
        }
      }
    }
  }

  private def calculateDataLookupCrc[F[_]: Monad](
      dataLookupList: List[(BlockHash, BlockSummary)]
  ): Crc32[F] =
    Crc32[F](
      dataLookupList
        .foldLeft(ByteString.EMPTY) {
          case (byteString, (_, blockSummary)) =>
            val blockBytes = blockSummary.toByteString
            byteString.concat(blockBytes.size().toByteString.concat(blockBytes))
        }
        .toByteArray
    )

  private def readDataLookupData[F[_]: Sync](
      randomAccessIO: RandomAccessIO[F]
  ): F[List[(BlockHash, BlockSummary)]] = {
    def readRec(
        result: List[(BlockHash, BlockSummary)]
    ): F[List[(BlockHash, BlockSummary)]] =
      for {
        blockSizeOpt <- randomAccessIO.readInt
        result <- blockSizeOpt match {
                   case Some(blockSize) =>
                     val blockMetaBytes = Array.ofDim[Byte](blockSize)
                     for {
                       _            <- randomAccessIO.readFully(blockMetaBytes)
                       blockSummary <- Sync[F].delay { BlockSummary.parseFrom(blockMetaBytes) }
                       result       <- readRec((blockSummary.blockHash -> blockSummary) :: result)
                     } yield result
                   case None =>
                     result.reverse.pure[F]
                 }
      } yield result
    readRec(List.empty)
  }

  private def validateDataLookupData[F[_]: Monad](
      dataLookupRandomAccessFile: RandomAccessIO[F],
      readDataLookupCrc: Long,
      dataLookupList: List[(BlockHash, BlockSummary)]
  ): F[(List[(BlockHash, BlockSummary)], Crc32[F])] = {
    val fullCalculatedCrc = calculateDataLookupCrc[F](dataLookupList)
    fullCalculatedCrc.value.flatMap { fullCalculatedCrcValue =>
      if (fullCalculatedCrcValue == readDataLookupCrc) {
        (dataLookupList, fullCalculatedCrc).pure[F]
      } else if (dataLookupList.nonEmpty) {
        val withoutLastCalculatedCrc = calculateDataLookupCrc[F](dataLookupList.init)
        withoutLastCalculatedCrc.value.flatMap { withoutLastCalculatedCrcValue =>
          if (withoutLastCalculatedCrcValue == readDataLookupCrc) {
            val (_, blockSummary)             = dataLookupList.last
            val byteString                    = blockSummary.toByteString
            val lastDataLookupEntrySize: Long = 4L + byteString.size()
            for {
              length <- dataLookupRandomAccessFile.length
              _      <- dataLookupRandomAccessFile.setLength(length - lastDataLookupEntrySize)
            } yield (dataLookupList.init, withoutLastCalculatedCrc)
          } else {
            // TODO: Restore data lookup from block storage
            dataLookupRandomAccessFile.setLength(0)
            (List.empty[(BlockHash, BlockSummary)], Crc32.empty[F]()).pure[F]
          }
        }
      } else {
        // TODO: Restore data lookup from block storage
        dataLookupRandomAccessFile.setLength(0)
        (List.empty[(BlockHash, BlockSummary)], Crc32.empty[F]()).pure[F]
      }
    }
  }

  private def extractChildAndJustificationMap(
      dataLookup: List[(BlockHash, BlockSummary)]
  ): ChildrenMapAndJustificationMap =
    dataLookup.foldLeft(
      dataLookup
        .map {
          case (blockHash, _) =>
            (blockHash -> Set.empty[BlockHash], blockHash -> Set.empty[BlockHash])
        }
        .unzip
        .bimap(_.toMap, _.toMap)
    ) {
      case ((childMap, justificationMap), (_, blockSummary)) =>
        val updatedChildMap = blockSummary.parents.foldLeft(childMap) {
          case (acc, p) =>
            val currentChildren = acc.getOrElse(p, Set.empty[BlockHash])
            acc.updated(p, currentChildren + blockSummary.blockHash)
        }
        val updatedJustificationMap =
          blockSummary.justifications.foldLeft(justificationMap) {
            case (acc, justification) =>
              val currBlockWithSpecifyJustification =
                acc.getOrElse(justification.latestBlockHash, Set.empty[BlockHash])
              acc.updated(
                justification.latestBlockHash,
                currBlockWithSpecifyJustification + blockSummary.blockHash
              )
          }
        (updatedChildMap, updatedJustificationMap)
    }

  private def extractTopoSort[F[_]: MonadError[?[_], Throwable]](
      dataLookup: List[(BlockHash, BlockSummary)]
  ): F[Vector[Vector[BlockHash]]] = {
    val blockSummarys = dataLookup.map(_._2).toVector
    val indexedTopoSort =
      blockSummarys.groupBy(_.rank).mapValues(_.map(_.blockHash)).toVector.sortBy(_._1)
    val topoSortInvariant = indexedTopoSort.zipWithIndex.forall {
      case ((readI, _), i) => readI == i
    }
    assertCond[F]("Topo sort invariant is violated", topoSortInvariant) map kp(
      indexedTopoSort.map(_._2)
    )
  }

  private def loadCheckpoints[F[_]: Sync: Log: RaiseIOError](
      checkpointsDirPath: Path
  ): F[List[Checkpoint]] =
    for {
      _     <- makeDirectory[F](checkpointsDirPath)
      files <- listRegularFiles[F](checkpointsDirPath)
      checkpoints <- files.flatTraverse { filePath =>
                      filePath.getFileName.toString match {
                        case checkpointPattern(start, end) =>
                          List(Checkpoint(start.toLong, end.toLong, filePath, None)).pure[F]
                        case other =>
                          Log[F].warn(s"Ignoring file '$other': not a valid checkpoint name") *>
                            List.empty[Checkpoint].pure[F]
                      }
                    }
      sortedCheckpoints = checkpoints.sortBy(_.start)
      result <- if (sortedCheckpoints.headOption.forall(_.start == 0)) {
                 if (sortedCheckpoints.isEmpty ||
                     sortedCheckpoints.zip(sortedCheckpoints.tail).forall {
                       case (current, next) => current.end == next.start
                     }) {
                   sortedCheckpoints.pure[F]
                 } else {
                   Sync[F].raiseError(CheckpointsAreNotConsecutive(sortedCheckpoints.map(_.path)))
                 }
               } else {
                 Sync[F].raiseError(CheckpointsDoNotStartFromZero(sortedCheckpoints.map(_.path)))
               }
    } yield result

  def create[F[_]: Concurrent: Log: BlockStorage: Metrics](
      config: Config
  ): F[FileDagStorage[F]] = {
    implicit val raiseIOError: RaiseIOError[F] = IOError.raiseIOErrorThroughSync[F]
    for {
      lock                  <- Semaphore[F](1)
      readLatestMessagesCrc <- readCrc[F](config.latestMessagesCrcPath)
      latestMessagesFileResource = Resource.make(
        RandomAccessIO.open[F](config.latestMessagesLogPath, RandomAccessIO.ReadWrite)
      )(_.close)
      latestMessagesResult <- latestMessagesFileResource.use { latestMessagesFile =>
                               for {
                                 latestMessagesReadResult <- readLatestMessagesData(
                                                              latestMessagesFile
                                                            )
                                 (latestMessagesList, logSize) = latestMessagesReadResult
                                 result <- validateLatestMessagesData[F](
                                            latestMessagesFile,
                                            readLatestMessagesCrc,
                                            latestMessagesList
                                          )
                                 (latestMessagesMap, calculatedLatestMessagesCrc) = result
                               } yield (latestMessagesMap, calculatedLatestMessagesCrc, logSize)
                             }
      (latestMessagesMap, calculatedLatestMessagesCrc, logSize) = latestMessagesResult
      readDataLookupCrc                                         <- readCrc[F](config.blockSummaryCrcPath)
      dataLookupFileResource = Resource.make(
        RandomAccessIO.open[F](config.blockSummaryLogPath, RandomAccessIO.ReadWrite)
      )(_.close)
      dataLookupResult <- dataLookupFileResource.use { randomAccessIO =>
                           for {
                             dataLookupList <- readDataLookupData(randomAccessIO)
                             result <- validateDataLookupData[F](
                                        randomAccessIO,
                                        readDataLookupCrc,
                                        dataLookupList
                                      )
                           } yield result
                         }
      (dataLookupList, calculatedDataLookupCrc) = dataLookupResult
      childAndJustificationMap                  = extractChildAndJustificationMap(dataLookupList)
      (childMap, justificationMap)              = childAndJustificationMap
      topoSort                                  <- extractTopoSort[F](dataLookupList)
      sortedCheckpoints                         <- loadCheckpoints(config.checkpointsDirPath)
      latestMessagesLogOutputStream <- FileOutputStreamIO.open[F](
                                        config.latestMessagesLogPath,
                                        true
                                      )
      blockSummaryLogOutputStream <- FileOutputStreamIO.open[F](
                                      config.blockSummaryLogPath,
                                      true
                                    )
      state = FileDagStorageState(
        latestMessages = latestMessagesMap,
        childMap = childMap,
        justificationMap = justificationMap,
        dataLookup = dataLookupList.toMap,
        topoSort = topoSort,
        sortOffset = sortedCheckpoints.lastOption.map(_.end).getOrElse(0L),
        checkpoints = sortedCheckpoints,
        latestMessagesLogOutputStream = latestMessagesLogOutputStream,
        latestMessagesLogSize = logSize,
        latestMessagesCrc = calculatedLatestMessagesCrc,
        blockSummaryLogOutputStream = blockSummaryLogOutputStream,
        blockSummaryCrc = calculatedDataLookupCrc
      )

      res <- fileDagStorageState[F](config, lock, state)
    } yield res
  }

  def createEmptyFromGenesis[F[_]: Concurrent: Log: BlockStorage: Metrics](
      config: Config,
      genesis: Block
  ): F[FileDagStorage[F]] = {
    implicit val raiseIOError: RaiseIOError[F] = IOError.raiseIOErrorThroughSync[F]
    for {
      lock                  <- Semaphore[F](1)
      _                     <- createFile[F](config.latestMessagesLogPath)
      _                     <- createFile[F](config.latestMessagesCrcPath)
      genesisBonds          = genesis.getHeader.getState.bonds
      initialLatestMessages = genesisBonds.map(_.validatorPublicKey -> genesis.blockHash).toMap
      latestMessagesData = initialLatestMessages
        .foldLeft(ByteString.EMPTY) {
          case (byteString, (validator, blockHash)) =>
            byteString.concat(validator).concat(blockHash)
        }
        .toByteArray
      latestMessagesCrc <- Crc32.emptyF[F]()
      _ <- initialLatestMessages.toList.traverse_ {
            case (validator, blockHash) =>
              latestMessagesCrc.update(validator.concat(blockHash).toByteArray)
          }
      latestMessagesCrcBytes <- latestMessagesCrc.bytes
      _                      <- writeToFile[F](config.latestMessagesLogPath, latestMessagesData)
      _                      <- writeToFile[F](config.latestMessagesCrcPath, latestMessagesCrcBytes)
      blockSummaryCrc        <- Crc32.emptyF[F]()
      genesisByteString      = genesis.toByteString
      genesisData            = genesisByteString.size.toByteString.concat(genesisByteString).toByteArray
      _                      <- blockSummaryCrc.update(genesisData)
      blockSummaryCrcBytes   <- blockSummaryCrc.bytes
      _                      <- writeToFile[F](config.blockSummaryLogPath, genesisData)
      _                      <- writeToFile[F](config.blockSummaryCrcPath, blockSummaryCrcBytes)
      latestMessagesLogOutputStream <- FileOutputStreamIO.open[F](
                                        config.latestMessagesLogPath,
                                        true
                                      )
      blockSummaryLogOutputStream <- FileOutputStreamIO.open[F](
                                      config.blockSummaryLogPath,
                                      true
                                    )
      state = FileDagStorageState(
        latestMessages = initialLatestMessages,
        childMap = Map(genesis.blockHash         -> Set.empty[BlockHash]),
        justificationMap = Map(genesis.blockHash -> Set.empty[BlockHash]),
        dataLookup = Map(genesis.blockHash       -> BlockSummary.fromBlock(genesis)),
        topoSort = Vector(Vector(genesis.blockHash)),
        sortOffset = 0L,
        checkpoints = List.empty,
        latestMessagesLogOutputStream = latestMessagesLogOutputStream,
        latestMessagesLogSize = initialLatestMessages.size,
        latestMessagesCrc = latestMessagesCrc,
        blockSummaryLogOutputStream = blockSummaryLogOutputStream,
        blockSummaryCrc = blockSummaryCrc
      )

      res <- fileDagStorageState[F](config, lock, state)
    } yield res
  }

  private def fileDagStorageState[F[_]: Concurrent: Log: BlockStorage: RaiseIOError](
      config: Config,
      lock: Semaphore[F],
      state: FileDagStorageState[F]
  )(implicit met: Metrics[F]) =
    state.useStateByRef[F](
      new FileDagStorage[F](
        lock,
        config.latestMessagesLogPath,
        config.latestMessagesCrcPath,
        config.latestMessagesLogMaxSizeFactor,
        config.blockSummaryLogPath,
        config.blockSummaryCrcPath,
        _
      ) with MeteredDagStorage[F] {
        override implicit val m: Metrics[F] = met
        override implicit val ms: Source    = Metrics.Source(DagStorageMetricsSource, "file")
        override implicit val a: Apply[F]   = Concurrent[F]
      }
    )

  def apply[F[_]: Concurrent: Log: RaiseIOError: Metrics](
      dagStoragePath: Path,
      latestMessagesLogMaxSizeFactor: Int,
      blockStorage: BlockStorage[F]
  ): Resource[F, DagStorage[F]] =
    Resource.make {
      for {
        _      <- fileIO.makeDirectory(dagStoragePath)
        config = Config(dagStoragePath, latestMessagesLogMaxSizeFactor)
        storage <- FileDagStorage.create(config)(
                    Concurrent[F],
                    Log[F],
                    blockStorage,
                    Metrics[F]
                  )
      } yield storage
    }(_.close()).widen
}
