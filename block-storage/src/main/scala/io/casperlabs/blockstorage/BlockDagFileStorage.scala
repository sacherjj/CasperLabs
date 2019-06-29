package io.casperlabs.blockstorage

import java.nio.file.{Path, Paths, StandardCopyOption}
import java.nio.{BufferUnderflowException, ByteBuffer}

import cats.{Apply, Monad, MonadError}
import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import cats.mtl.MonadState
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockDagFileStorage.{Checkpoint, CheckpointedDagInfo}
import io.casperlabs.blockstorage.BlockDagRepresentation.Validator
import io.casperlabs.blockstorage.BlockDagStorage.MeteredBlockDagStorage
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.util.byteOps._
import io.casperlabs.blockstorage.util.fileIO.IOError.RaiseIOError
import io.casperlabs.blockstorage.util.fileIO._
import io.casperlabs.blockstorage.util.fileIO.IOError
import io.casperlabs.blockstorage.util.{fileIO, Crc32, TopologicalSortUtil}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.configuration.{ignore, relativeToDataDir, SubConfig}
import io.casperlabs.catscontrib.MonadStateOps._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.shared.{Log, LogSource}

import scala.ref.WeakReference
import scala.util.matching.Regex

private final case class BlockDagFileStorageState[F[_]: Sync](
    latestMessages: Map[Validator, BlockHash],
    childMap: Map[BlockHash, Set[BlockHash]],
    dataLookup: Map[BlockHash, BlockMetadata],
    // Top layers of the DAG, rank by rank.
    topoSort: Vector[Vector[BlockHash]],
    // The rank of the blocks in `topoSort.head`. Everything before it is in checkpoints.
    sortOffset: Long,
    checkpoints: List[Checkpoint],
    latestMessagesLogOutputStream: FileOutputStreamIO[F],
    latestMessagesLogSize: Int,
    latestMessagesCrc: Crc32[F],
    blockMetadataLogOutputStream: FileOutputStreamIO[F],
    blockMetadataCrc: Crc32[F]
)

class BlockDagFileStorage[F[_]: Concurrent: Log: BlockStore: RaiseIOError] private (
    lock: Semaphore[F],
    latestMessagesDataFilePath: Path,
    latestMessagesCrcFilePath: Path,
    latestMessagesLogMaxSizeFactor: Int,
    blockMetadataLogPath: Path,
    blockMetadataCrcPath: Path,
    state: MonadState[F, BlockDagFileStorageState[F]]
) extends BlockDagStorage[F] {
  import BlockDagFileStorage._

  private implicit val logSource = LogSource(BlockDagFileStorage.getClass)

  private case class FileDagRepresentation(
      latestMessagesMap: Map[Validator, BlockHash],
      childMap: Map[BlockHash, Set[BlockHash]],
      dataLookup: Map[BlockHash, BlockMetadata],
      topoSortVector: Vector[Vector[BlockHash]],
      sortOffset: Long
  ) extends BlockDagRepresentation[F] {

    // Number of the last rank in topoSortVector
    def sortEndBlockNumber = sortOffset + topoSortVector.size - 1

    def children(blockHash: BlockHash): F[Option[Set[BlockHash]]] =
      for {
        result <- childMap.get(blockHash) match {
                   case Some(children) =>
                     Option(children).pure[F]
                   case None =>
                     for {
                       blockOpt <- BlockStore[F].getBlockMessage(blockHash)
                       result <- blockOpt match {
                                  case Some(block) =>
                                    val number = block.getHeader.rank
                                    if (number >= sortOffset) {
                                      none[Set[BlockHash]].pure[F]
                                    } else {
                                      lock.withPermit(
                                        for {
                                          oldDagInfo <- loadCheckpoint(number)
                                        } yield oldDagInfo.flatMap(_.childMap.get(blockHash))
                                      )
                                    }
                                  case None => none[Set[BlockHash]].pure[F]
                                }
                     } yield result
                 }
      } yield result

    def lookup(blockHash: BlockHash): F[Option[BlockMetadata]] =
      dataLookup
        .get(blockHash)
        .fold(
          BlockStore[F].getBlockMessage(blockHash).map(_.map(BlockMetadata.fromBlock))
        )(blockMetadata => Option(blockMetadata).pure[F])

    def contains(blockHash: BlockHash): F[Boolean] =
      dataLookup.get(blockHash).fold(BlockStore[F].contains(blockHash))(_ => true.pure[F])

    def topoSort(startBlockNumber: Long): F[Vector[Vector[BlockHash]]] =
      topoSort(startBlockNumber, sortEndBlockNumber)

    def topoSort(startBlockNumber: Long, endBlockNumber: Long): F[Vector[Vector[BlockHash]]] = {
      val length = endBlockNumber - startBlockNumber + 1
      if (length > Int.MaxValue) { // Max Vector length
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
    }

    def topoSortTail(tailLength: Int): F[Vector[Vector[BlockHash]]] = {
      val endBlockNumber   = sortEndBlockNumber
      val startBlockNumber = Math.max(0L, endBlockNumber - tailLength + 1)
      topoSort(startBlockNumber, endBlockNumber)
    }

    def deriveOrdering(startBlockNumber: Long): F[Ordering[BlockMetadata]] =
      topoSort(startBlockNumber).map { topologicalSorting =>
        val order = topologicalSorting.flatten.zipWithIndex.toMap
        Ordering.by(b => order(b.blockHash))
      }

    def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
      latestMessagesMap.get(validator).pure[F]

    def latestMessage(validator: Validator): F[Option[BlockMetadata]] =
      latestMessagesMap.get(validator).flatTraverse(lookup)

    def latestMessageHashes: F[Map[Validator, BlockHash]] =
      latestMessagesMap.pure[F]

    def latestMessages: F[Map[Validator, BlockMetadata]] =
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
      blockMetadataList <- checkpointDataInputResource.use(readDataLookupData[F])
      dataLookup        = blockMetadataList.toMap
      childMap          = extractChildMap(blockMetadataList)
      topoSort          <- extractTopoSort[F](blockMetadataList)
    } yield CheckpointedDagInfo(childMap, dataLookup, topoSort, checkpoint.start)
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

  private def updateDataLookupFile(blockMetadata: BlockMetadata): F[Unit] =
    for {
      dataLookupCrc          <- (state >> 'blockMetadataCrc).get
      blockBytes             = blockMetadata.toByteString
      toAppend               = blockBytes.size.toByteString.concat(blockBytes).toByteArray
      dataLookupOutputStream <- (state >> 'blockMetadataLogOutputStream).get
      _                      <- dataLookupOutputStream.write(toAppend)
      _                      <- dataLookupOutputStream.flush
      _                      <- dataLookupCrc.update(toAppend)
      _                      <- updateDataLookupCrcFile(dataLookupCrc)
    } yield ()

  private def updateDataLookupCrcFile(newCrc: Crc32[F]): F[Unit] =
    for {
      newCrcBytes <- newCrc.bytes
      tmpCrc      <- createSameDirectoryTemporaryFile(blockMetadataCrcPath)
      _           <- writeToFile[F](tmpCrc, newCrcBytes)
      _           <- replaceFile(tmpCrc, blockMetadataCrcPath)
    } yield ()

  private def representation: F[BlockDagRepresentation[F]] =
    (
      (state >> 'latestMessages).get,
      (state >> 'childMap).get,
      (state >> 'dataLookup).get,
      (state >> 'topoSort).get,
      (state >> 'sortOffset).get
    ) mapN FileDagRepresentation.apply

  def getRepresentation: F[BlockDagRepresentation[F]] =
    lock.withPermit(representation)

  def insert(block: Block): F[BlockDagRepresentation[F]] =
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
      _             <- squashLatestMessagesDataFileIfNeeded()
      blockMetadata = BlockMetadata.fromBlock(block)
      _ <- assertCond[F](
            "Cannot insert block: blockHash.size != 32",
            block.blockHash.size == 32
          )
      _ <- (state >> 'dataLookup).modify(_.updated(block.blockHash, blockMetadata))
      _ <- (state >> 'childMap).modify(
            block.getHeader.parentHashes
              .foldLeft(_) {
                case (acc, p) =>
                  val currChildren = acc.getOrElse(p, Set.empty[BlockHash])
                  acc.updated(p, currChildren + block.blockHash)
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
      newValidatorsWithSender <- if (validator.isEmpty) {
                                  // Ignore empty sender for special cases such as genesis block
                                  Log[F].warn(
                                    s"Block ${Base16.encode(block.blockHash.toByteArray)} validator is empty"
                                  ) *> newValidators.pure[F]
                                } else if (validator.size == 32) {
                                  (newValidators + validator).pure[F]
                                } else {
                                  Sync[F].raiseError[Set[ByteString]](
                                    BlockValidatorIsMalformed(block)
                                  )
                                }
      _ <- (state >> 'latestMessages).modify {
            newValidatorsWithSender.foldLeft(_) {
              //Update new validators with block in which
              //they were bonded (i.e. this block)
              case (acc, v) => acc.updated(v, block.blockHash)
            }
          }
      _ <- updateLatestMessagesFile(
            newValidatorsWithSender.toList,
            block.blockHash
          )
      _ <- updateDataLookupFile(blockMetadata)
    } yield ()

  def checkpoint(): F[Unit] =
    ().pure[F]

  def clear(): F[Unit] =
    lock.withPermit(
      for {
        latestMessagesLogOutputStream <- (state >> 'latestMessagesLogOutputStream).get
        _                             <- latestMessagesLogOutputStream.close
        blockMetadataLogOutputStream  <- (state >> 'blockMetadataLogOutputStream).get
        _                             <- blockMetadataLogOutputStream.close
        _                             <- writeToFile(latestMessagesDataFilePath, Array.emptyByteArray)
        _                             <- writeToFile(blockMetadataLogPath, Array.emptyByteArray)
        newLatestMessagesCrc          <- Crc32.emptyF[F]()
        newLatestMessagesCrcBytes     <- newLatestMessagesCrc.bytes
        _                             <- writeToFile(latestMessagesCrcFilePath, newLatestMessagesCrcBytes)
        newBlockMetadataCrc           <- Crc32.emptyF[F]()
        newBlockMetadataCrcBytes      <- newBlockMetadataCrc.bytes
        _                             <- writeToFile(blockMetadataCrcPath, newBlockMetadataCrcBytes)
        _                             <- (state >> 'dataLookup).set(Map.empty)
        _                             <- (state >> 'childMap).set(Map.empty)
        _                             <- (state >> 'topoSort).set(Vector.empty)
        _                             <- (state >> 'latestMessages).set(Map.empty)
        newLatestMessagesLogOutputStream <- FileOutputStreamIO
                                             .open[F](latestMessagesDataFilePath, true)
        _                               <- (state >> 'latestMessagesLogOutputStream).set(newLatestMessagesLogOutputStream)
        newBlockMetadataLogOutputStream <- FileOutputStreamIO.open[F](blockMetadataLogPath, true)
        _                               <- (state >> 'blockMetadataLogOutputStream).set(newBlockMetadataLogOutputStream)
        _                               <- (state >> 'latestMessagesLogSize).set(0)
        _                               <- (state >> 'latestMessagesCrc).set(newLatestMessagesCrc)
        _                               <- (state >> 'blockMetadataCrc).set(newBlockMetadataCrc)
      } yield ()
    )

  def close(): F[Unit] =
    lock.withPermit(
      for {
        latestMessagesLogOutputStream <- (state >> 'latestMessagesLogOutputStream).get
        _                             <- latestMessagesLogOutputStream.close
        blockMetadataLogOutputStream  <- (state >> 'blockMetadataLogOutputStream).get
        _                             <- blockMetadataLogOutputStream.close
      } yield ()
    )
}

object BlockDagFileStorage {
  private implicit val logSource       = LogSource(BlockDagFileStorage.getClass)
  private val checkpointPattern: Regex = "([0-9]+)-([0-9]+)".r

  final case class Config(
      @ignore
      @relativeToDataDir("block-dag-file-storage")
      dir: Path = Paths.get("nonreachable"),
      latestMessagesLogMaxSizeFactor: Int = 10
  ) extends SubConfig {
    val latestMessagesLogPath: Path = dir.resolve("latest-messages-log")
    val latestMessagesCrcPath: Path = dir.resolve("latest-messages-crc")
    val blockMetadataLogPath: Path  = dir.resolve("block-metadata-log")
    val blockMetadataCrcPath: Path  = dir.resolve("block-metadata-crc")
    val checkpointsDirPath: Path    = dir.resolve("checkpoints")
  }

  def assertCond[F[_]: MonadError[?[_], Throwable]](errMsg: String, b: => Boolean): F[Unit] =
    MonadError[F, Throwable].raiseError(new IllegalArgumentException(errMsg)).whenA(!b)

  private[blockstorage] final case class CheckpointedDagInfo(
      childMap: Map[BlockHash, Set[BlockHash]],
      dataLookup: Map[BlockHash, BlockMetadata],
      topoSort: Vector[Vector[BlockHash]],
      sortOffset: Long
  )

  private[blockstorage] final case class Checkpoint(
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
      dataLookupList: List[(BlockHash, BlockMetadata)]
  ): Crc32[F] =
    Crc32[F](
      dataLookupList
        .foldLeft(ByteString.EMPTY) {
          case (byteString, (_, blockMetadata)) =>
            val blockBytes = blockMetadata.toByteString
            byteString.concat(blockBytes.size().toByteString.concat(blockBytes))
        }
        .toByteArray
    )

  private def readDataLookupData[F[_]: Sync](
      randomAccessIO: RandomAccessIO[F]
  ): F[List[(BlockHash, BlockMetadata)]] = {
    def readRec(
        result: List[(BlockHash, BlockMetadata)]
    ): F[List[(BlockHash, BlockMetadata)]] =
      for {
        blockSizeOpt <- randomAccessIO.readInt
        result <- blockSizeOpt match {
                   case Some(blockSize) =>
                     val blockMetaBytes = Array.ofDim[Byte](blockSize)
                     for {
                       _             <- randomAccessIO.readFully(blockMetaBytes)
                       blockMetadata <- Sync[F].delay { BlockMetadata.fromBytes(blockMetaBytes) }
                       result        <- readRec((blockMetadata.blockHash -> blockMetadata) :: result)
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
      dataLookupList: List[(BlockHash, BlockMetadata)]
  ): F[(List[(BlockHash, BlockMetadata)], Crc32[F])] = {
    val fullCalculatedCrc = calculateDataLookupCrc[F](dataLookupList)
    fullCalculatedCrc.value.flatMap { fullCalculatedCrcValue =>
      if (fullCalculatedCrcValue == readDataLookupCrc) {
        (dataLookupList, fullCalculatedCrc).pure[F]
      } else if (dataLookupList.nonEmpty) {
        val withoutLastCalculatedCrc = calculateDataLookupCrc[F](dataLookupList.init)
        withoutLastCalculatedCrc.value.flatMap { withoutLastCalculatedCrcValue =>
          if (withoutLastCalculatedCrcValue == readDataLookupCrc) {
            val (_, blockMetadata)            = dataLookupList.last
            val byteString                    = blockMetadata.toByteString
            val lastDataLookupEntrySize: Long = 4L + byteString.size()
            for {
              length <- dataLookupRandomAccessFile.length
              _      <- dataLookupRandomAccessFile.setLength(length - lastDataLookupEntrySize)
            } yield (dataLookupList.init, withoutLastCalculatedCrc)
          } else {
            // TODO: Restore data lookup from block storage
            dataLookupRandomAccessFile.setLength(0)
            (List.empty[(BlockHash, BlockMetadata)], Crc32.empty[F]()).pure[F]
          }
        }
      } else {
        // TODO: Restore data lookup from block storage
        dataLookupRandomAccessFile.setLength(0)
        (List.empty[(BlockHash, BlockMetadata)], Crc32.empty[F]()).pure[F]
      }
    }
  }

  private def extractChildMap(
      dataLookup: List[(BlockHash, BlockMetadata)]
  ): Map[BlockHash, Set[BlockHash]] =
    dataLookup.foldLeft(
      dataLookup.map { case (blockHash, _) => blockHash -> Set.empty[BlockHash] }.toMap
    ) {
      case (childMap, (_, blockMetadata)) =>
        blockMetadata.parents.foldLeft(childMap) {
          case (acc, p) =>
            val currentChildren = acc.getOrElse(p, Set.empty[BlockHash])
            acc.updated(p, currentChildren + blockMetadata.blockHash)
        }
    }

  private def extractTopoSort[F[_]: MonadError[?[_], Throwable]](
      dataLookup: List[(BlockHash, BlockMetadata)]
  ): F[Vector[Vector[BlockHash]]] = {
    val blockMetadatas = dataLookup.map(_._2).toVector
    val indexedTopoSort =
      blockMetadatas.groupBy(_.rank).mapValues(_.map(_.blockHash)).toVector.sortBy(_._1)
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

  def create[F[_]: Concurrent: Log: BlockStore: Metrics](
      config: Config
  ): F[BlockDagFileStorage[F]] = {
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
      readDataLookupCrc                                         <- readCrc[F](config.blockMetadataCrcPath)
      dataLookupFileResource = Resource.make(
        RandomAccessIO.open[F](config.blockMetadataLogPath, RandomAccessIO.ReadWrite)
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
      childMap                                  = extractChildMap(dataLookupList)
      topoSort                                  <- extractTopoSort[F](dataLookupList)
      sortedCheckpoints                         <- loadCheckpoints(config.checkpointsDirPath)
      latestMessagesLogOutputStream <- FileOutputStreamIO.open[F](
                                        config.latestMessagesLogPath,
                                        true
                                      )
      blockMetadataLogOutputStream <- FileOutputStreamIO.open[F](
                                       config.blockMetadataLogPath,
                                       true
                                     )
      state = BlockDagFileStorageState(
        latestMessages = latestMessagesMap,
        childMap = childMap,
        dataLookup = dataLookupList.toMap,
        topoSort = topoSort,
        sortOffset = sortedCheckpoints.lastOption.map(_.end).getOrElse(0L),
        checkpoints = sortedCheckpoints,
        latestMessagesLogOutputStream = latestMessagesLogOutputStream,
        latestMessagesLogSize = logSize,
        latestMessagesCrc = calculatedLatestMessagesCrc,
        blockMetadataLogOutputStream = blockMetadataLogOutputStream,
        blockMetadataCrc = calculatedDataLookupCrc
      )

      res <- blockDagFileStorageState[F](config, lock, state)
    } yield res
  }

  def createEmptyFromGenesis[F[_]: Concurrent: Log: BlockStore: Metrics](
      config: Config,
      genesis: Block
  ): F[BlockDagFileStorage[F]] = {
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
      blockMetadataCrc       <- Crc32.emptyF[F]()
      genesisByteString      = genesis.toByteString
      genesisData            = genesisByteString.size.toByteString.concat(genesisByteString).toByteArray
      _                      <- blockMetadataCrc.update(genesisData)
      blockMetadataCrcBytes  <- blockMetadataCrc.bytes
      _                      <- writeToFile[F](config.blockMetadataLogPath, genesisData)
      _                      <- writeToFile[F](config.blockMetadataCrcPath, blockMetadataCrcBytes)
      latestMessagesLogOutputStream <- FileOutputStreamIO.open[F](
                                        config.latestMessagesLogPath,
                                        true
                                      )
      blockMetadataLogOutputStream <- FileOutputStreamIO.open[F](
                                       config.blockMetadataLogPath,
                                       true
                                     )
      state = BlockDagFileStorageState(
        latestMessages = initialLatestMessages,
        childMap = Map(genesis.blockHash   -> Set.empty[BlockHash]),
        dataLookup = Map(genesis.blockHash -> BlockMetadata.fromBlock(genesis)),
        topoSort = Vector(Vector(genesis.blockHash)),
        sortOffset = 0L,
        checkpoints = List.empty,
        latestMessagesLogOutputStream = latestMessagesLogOutputStream,
        latestMessagesLogSize = initialLatestMessages.size,
        latestMessagesCrc = latestMessagesCrc,
        blockMetadataLogOutputStream = blockMetadataLogOutputStream,
        blockMetadataCrc = blockMetadataCrc
      )

      res <- blockDagFileStorageState[F](config, lock, state)
    } yield res
  }

  private def blockDagFileStorageState[F[_]: Concurrent: Log: BlockStore: RaiseIOError](
      config: Config,
      lock: Semaphore[F],
      state: BlockDagFileStorageState[F]
  )(implicit met: Metrics[F]) =
    state.useStateByRef[F](
      new BlockDagFileStorage[F](
        lock,
        config.latestMessagesLogPath,
        config.latestMessagesCrcPath,
        config.latestMessagesLogMaxSizeFactor,
        config.blockMetadataLogPath,
        config.blockMetadataCrcPath,
        _
      ) with MeteredBlockDagStorage[F] {
        override implicit val m: Metrics[F] = met
        override implicit val ms: Source    = Metrics.Source(BlockDagStorageMetricsSource, "file")
        override implicit val a: Apply[F]   = Concurrent[F]
      }
    )

  def apply[F[_]: Concurrent: Log: RaiseIOError: Metrics](
      dagStoragePath: Path,
      latestMessagesLogMaxSizeFactor: Int,
      blockStore: BlockStore[F]
  ): Resource[F, BlockDagStorage[F]] =
    Resource.make {
      for {
        _      <- fileIO.makeDirectory(dagStoragePath)
        config = Config(dagStoragePath, latestMessagesLogMaxSizeFactor)
        storage <- BlockDagFileStorage.create(config)(
                    Concurrent[F],
                    Log[F],
                    blockStore,
                    Metrics[F]
                  )
      } yield storage
    }(_.close()).widen
}
