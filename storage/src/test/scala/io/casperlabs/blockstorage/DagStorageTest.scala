package io.casperlabs.blockstorage

import java.nio.file.StandardOpenOption

import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.DagRepresentation.Validator
import io.casperlabs.blockstorage.BlockStorage.BlockHash
import io.casperlabs.blockstorage.util.byteOps._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.blockstorage.blockImplicits._
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps._
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.util.Random

@silent("match may not be exhaustive")
trait DagStorageTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll {
  val scheduler = Scheduler.fixedPool("dag-storage-test-scheduler", 4)

  def withDagStorage[R](f: DagStorage[Task] => Task[R]): R

  "DAG Storage" should "be able to lookup a stored block" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      withDagStorage { dagStorage =>
        for {
          _ <- blockElements.traverse_(
                blockMsgWithTransform => dagStorage.insert(blockMsgWithTransform.getBlockMessage)
              )
          dag <- dagStorage.getRepresentation
          blockElementLookups <- blockElements.traverse {
                                  case BlockMsgWithTransform(Some(b), _) =>
                                    for {
                                      blockMetadata <- dag.lookup(b.blockHash)
                                      latestMessageHash <- dag.latestMessageHash(
                                                            b.getHeader.validatorPublicKey
                                                          )
                                      latestMessage <- dag.latestMessage(
                                                        b.getHeader.validatorPublicKey
                                                      )
                                    } yield (blockMetadata, latestMessageHash, latestMessage)
                                }
          latestMessageHashes <- dag.latestMessageHashes
          latestMessages      <- dag.latestMessages
          _                   <- dagStorage.clear()
          _ = blockElementLookups.zip(blockElements).foreach {
            case (
                (blockMetadata, latestMessageHash, latestMessage),
                BlockMsgWithTransform(Some(b), _)
                ) =>
              blockMetadata shouldBe Some(BlockMetadata.fromBlock(b))
              latestMessageHash shouldBe Some(b.blockHash)
              latestMessage shouldBe Some(BlockMetadata.fromBlock(b))
          }
          _      = latestMessageHashes.size shouldBe blockElements.size
          result = latestMessages.size shouldBe blockElements.size
        } yield result
      }
    }
  }
}

@silent("match may not be exhaustive")
class FileDagStorageTest extends DagStorageTest {

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("casperlabs-dag-storage-test-")

  def withDagStorageLocation[R](f: (Path, BlockStorage[Task]) => Task[R]): R = {
    val testProgram = Sync[Task].bracket {
      Sync[Task].delay {
        (mkTmpDir(), mkTmpDir())
      }
    } {
      case (dagStorageDataDir, blockStorageDataDir) =>
        for {
          blockStorage <- createBlockStorage(blockStorageDataDir)
          result       <- f(dagStorageDataDir, blockStorage)
          _            <- blockStorage.close()
        } yield result
    } {
      case (dagStorageDataDir, blockStorageDataDir) =>
        Sync[Task].delay {
          dagStorageDataDir.recursivelyDelete()
          blockStorageDataDir.recursivelyDelete()
        }
    }
    testProgram.unsafeRunSync(scheduler)
  }

  override def withDagStorage[R](f: DagStorage[Task] => Task[R]): R =
    withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
      for {
        dagStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
        result     <- f(dagStorage)
        _          <- dagStorage.close()
      } yield result
    }

  private def defaultLatestMessagesLog(dagStorageDataDir: Path): Path =
    dagStorageDataDir.resolve("latest-messages-log")

  private def defaultBlockMetadataLog(dagStorageDataDir: Path): Path =
    dagStorageDataDir.resolve("block-metadata-log")

  private def defaultBlockMetadataCrc(dagStorageDataDir: Path): Path =
    dagStorageDataDir.resolve("block-metadata-crc")

  private def defaultCheckpointsDir(dagStorageDataDir: Path): Path =
    dagStorageDataDir.resolve("checkpoints")

  private def createBlockStorage(blockStorageDataDir: Path): Task[BlockStorage[Task]] = {
    implicit val log = new Log.NOPLog[Task]()
    implicit val met = new MetricsNOP[Task]
    val env          = Context.env(blockStorageDataDir, 100L * 1024L * 1024L * 4096L)
    FileLMDBIndexBlockStorage.create[Task](env, blockStorageDataDir).map(_.right.get)
  }

  private def createAtDefaultLocation(
      dagStorageDataDir: Path,
      maxSizeFactor: Int = 10
  )(implicit blockStorage: BlockStorage[Task]): Task[DagStorage[Task]] = {
    implicit val log = new shared.Log.NOPLog[Task]()
    implicit val met = new MetricsNOP[Task]
    FileDagStorage.create[Task](
      FileDagStorage.Config(
        dagStorageDataDir,
        maxSizeFactor
      )
    )
  }

  type LookupResult =
    (
        List[
          (
              Option[BlockMetadata],
              Option[BlockHash],
              Option[BlockMetadata],
              Set[BlockHash],
              Option[Set[BlockHash]],
              Boolean
          )
        ],
        Map[Validator, BlockHash],
        Map[Validator, BlockMetadata],
        Vector[Vector[BlockHash]],
        Vector[Vector[BlockHash]]
    )

  private def lookupElements(
      blockElements: List[BlockMsgWithTransform],
      storage: DagStorage[Task],
      topoSortStartBlockNumber: Long = 0,
      topoSortTailLength: Int = 5
  ): Task[LookupResult] =
    for {
      dag <- storage.getRepresentation
      list <- blockElements.traverse {
               case BlockMsgWithTransform(Some(b), _) =>
                 for {
                   blockMetadata                    <- dag.lookup(b.blockHash)
                   latestMessageHash                <- dag.latestMessageHash(b.getHeader.validatorPublicKey)
                   latestMessage                    <- dag.latestMessage(b.getHeader.validatorPublicKey)
                   children                         <- dag.children(b.blockHash)
                   blocksWithSpecifiedJustification <- dag.justificationToBlocks(b.blockHash)
                   contains                         <- dag.contains(b.blockHash)
                 } yield (
                   blockMetadata,
                   latestMessageHash,
                   latestMessage,
                   children,
                   blocksWithSpecifiedJustification,
                   contains
                 )
             }
      latestMessageHashes <- dag.latestMessageHashes
      latestMessages      <- dag.latestMessages
      topoSort            <- dag.topoSort(topoSortStartBlockNumber)
      topoSortTail        <- dag.topoSortTail(topoSortTailLength)
    } yield (list, latestMessageHashes, latestMessages, topoSort, topoSortTail)

  private def testLookupElementsResult(
      lookupResult: LookupResult,
      blockElements: List[Block],
      topoSortStartBlockNumber: Long = 0,
      topoSortTailLength: Int = 5
  ): Assertion = {
    val (list, latestMessageHashes, latestMessages, topoSort, topoSortTail) = lookupResult
    val realLatestMessages = blockElements.foldLeft(Map.empty[Validator, BlockMetadata]) {
      case (lm, b) =>
        // Ignore empty sender for genesis block
        if (b.getHeader.validatorPublicKey != ByteString.EMPTY)
          lm.updated(b.getHeader.validatorPublicKey, BlockMetadata.fromBlock(b))
        else
          lm
    }
    list.zip(blockElements).foreach {
      case (
          (
            blockMetadata,
            latestMessageHash,
            latestMessage,
            children,
            blocksWithSpecifiedJustification,
            contains
          ),
          b
          ) =>
        blockMetadata shouldBe Some(BlockMetadata.fromBlock(b))
        latestMessageHash shouldBe realLatestMessages
          .get(b.getHeader.validatorPublicKey)
          .map(_.blockHash)
        latestMessage shouldBe realLatestMessages.get(b.getHeader.validatorPublicKey)
        children shouldBe
          blockElements
            .filter(_.getHeader.parentHashes.contains(b.blockHash))
            .map(_.blockHash)
            .toSet
        blocksWithSpecifiedJustification shouldBe
          Some(
            blockElements
              .filter(_.getHeader.justifications.map(_.latestBlockHash).contains(b.blockHash))
              .map(_.blockHash)
              .toSet
          )
        contains shouldBe true
    }
    latestMessageHashes shouldBe realLatestMessages.mapValues(_.blockHash)
    latestMessages shouldBe realLatestMessages

    def normalize(topoSort: Vector[Vector[BlockHash]]): Vector[Vector[BlockHash]] =
      if (topoSort.size == 1 && topoSort.head.isEmpty)
        Vector.empty
      else
        topoSort

    val realTopoSort = normalize(Vector(blockElements.map(_.blockHash).toVector))
    topoSort shouldBe realTopoSort.drop(topoSortStartBlockNumber.toInt)
    topoSortTail shouldBe realTopoSort.takeRight(topoSortTailLength)
  }

  it should "be able to restore state on startup" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
        for {
          firstStorage  <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          _             <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
          _             <- firstStorage.close()
          secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          result        <- lookupElements(blockElements, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(result, blockElements.flatMap(_.blockMessage))
      }
    }
  }

  it should "be able to restore latest messages with genesis with empty sender field" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      val blockElementsWithGenesis = blockElements match {
        case x :: xs =>
          val block = x.getBlockMessage
          val genesis = x.withBlockMessage(
            block.withHeader(block.getHeader.withValidatorPublicKey(ByteString.EMPTY))
          )
          genesis :: xs
        case Nil =>
          Nil
      }
      withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
        for {
          firstStorage  <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          _             <- blockElementsWithGenesis.traverse_(b => firstStorage.insert(b.getBlockMessage))
          _             <- firstStorage.close()
          secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          result        <- lookupElements(blockElementsWithGenesis, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(result, blockElementsWithGenesis.flatMap(_.blockMessage))
      }
    }
  }

  it should "be able to restore state from the previous two instances" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { firstBlockElements =>
      forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { secondBlockElements =>
        withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
          for {
            firstStorage  <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
            _             <- firstBlockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
            _             <- firstStorage.close()
            secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
            _             <- secondBlockElements.traverse_(b => secondStorage.insert(b.getBlockMessage))
            _             <- secondStorage.close()
            thirdStorage  <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
            result        <- lookupElements(firstBlockElements ++ secondBlockElements, thirdStorage)
            _             <- thirdStorage.close()
          } yield testLookupElementsResult(
            result,
            (firstBlockElements ++ secondBlockElements).flatMap(_.blockMessage)
          )
        }
      }
    }
  }

  it should "be able to restore latest messages on startup with appended 64 garbage bytes" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
        for {
          firstStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          _            <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
          _            <- firstStorage.close()
          garbageBytes = Array.fill[Byte](64)(0)
          _            <- Sync[Task].delay { Random.nextBytes(garbageBytes) }
          _ <- Sync[Task].delay {
                Files.write(
                  defaultLatestMessagesLog(dagStorageDataDir),
                  garbageBytes,
                  StandardOpenOption.APPEND
                )
              }
          secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          result        <- lookupElements(blockElements, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(result, blockElements.flatMap(_.blockMessage))
      }
    }
  }

  it should "be able to restore data lookup on startup with appended garbage block metadata" in {
    forAll(blockElementsWithParentsGen, blockMsgWithTransformGen, minSize(0), sizeRange(10)) {
      (blockElements, garbageBlock) =>
        withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
          for {
            firstStorage      <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
            _                 <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
            _                 <- firstStorage.close()
            garbageByteString = BlockMetadata.fromBlock(garbageBlock.getBlockMessage).toByteString
            garbageBytes      = garbageByteString.size.toByteString.concat(garbageByteString).toByteArray
            _ <- Sync[Task].delay {
                  Files.write(
                    defaultBlockMetadataLog(dagStorageDataDir),
                    garbageBytes,
                    StandardOpenOption.APPEND
                  )
                }
            secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
            result        <- lookupElements(blockElements, secondStorage)
            _             <- secondStorage.close()
          } yield testLookupElementsResult(result, blockElements.flatMap(_.blockMessage))
        }
    }
  }

  it should "be able to handle fully corrupted latest messages log file" in withDagStorageLocation {
    (dagStorageDataDir, blockStorage) =>
      val garbageBytes = Array.fill[Byte](789)(0)
      for {
        _ <- Sync[Task].delay { Random.nextBytes(garbageBytes) }
        _ <- Sync[Task].delay {
              Files.write(defaultLatestMessagesLog(dagStorageDataDir), garbageBytes)
            }
        storage             <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
        dag                 <- storage.getRepresentation
        latestMessageHashes <- dag.latestMessageHashes
        latestMessages      <- dag.latestMessages
        _                   <- storage.close()
        _                   = latestMessageHashes.size shouldBe 0
        result              = latestMessages.size shouldBe 0
      } yield result
  }

  it should "be able to restore after squashing latest messages" in {
    forAll(blockElementsWithParentsGen, minSize(0), sizeRange(10)) { blockElements =>
      forAll(
        blockWithNewHashesGen(blockElements.flatMap(_.blockMessage)),
        blockWithNewHashesGen(blockElements.flatMap(_.blockMessage))
      ) { (secondBlockElements, thirdBlockElements) =>
        withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
          for {
            firstStorage  <- createAtDefaultLocation(dagStorageDataDir, 2)(blockStorage)
            _             <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
            _             <- secondBlockElements.traverse_(firstStorage.insert)
            _             <- thirdBlockElements.traverse_(firstStorage.insert)
            _             <- firstStorage.close()
            secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
            result        <- lookupElements(blockElements, secondStorage)
            _             <- secondStorage.close()
          } yield testLookupElementsResult(
            result,
            blockElements
              .flatMap(_.blockMessage)
              .toList ++ secondBlockElements ++ thirdBlockElements
          )
        }
      }
    }
  }

  it should "be able to load checkpoints" in {
    forAll(blockElementsWithParentsGen, minSize(1), sizeRange(2)) { blockElements =>
      withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
        for {
          firstStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          _ <- blockElements.traverse_(
                b =>
                  blockStorage.put(b.getBlockMessage.blockHash, b) *> firstStorage.insert(
                    b.getBlockMessage
                  )
              )
          _ <- firstStorage.close()
          _ <- Sync[Task].delay {
                Files.move(
                  defaultBlockMetadataLog(dagStorageDataDir),
                  defaultCheckpointsDir(dagStorageDataDir).resolve("0-1")
                )
                Files.delete(defaultBlockMetadataCrc(dagStorageDataDir))
              }
          secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          result        <- lookupElements(blockElements, secondStorage)
          _             <- secondStorage.close()
        } yield testLookupElementsResult(
          result,
          blockElements.flatMap(_.blockMessage)
        )
      }
    }
  }

  it should "be able to clear and continue working" in {
    forAll(blockElementsWithParentsGen, minSize(1), sizeRange(2)) { blockElements =>
      withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
        for {
          firstStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          _ <- blockElements.traverse_(
                b =>
                  blockStorage.put(b.getBlockMessage.blockHash, b) *> firstStorage.insert(
                    b.getBlockMessage
                  )
              )
          _             = firstStorage.close()
          secondStorage <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          elements      <- lookupElements(blockElements, secondStorage)
          _ = testLookupElementsResult(
            elements,
            blockElements.flatMap(_.blockMessage)
          )
          _      <- secondStorage.clear()
          _      <- blockStorage.clear()
          result <- lookupElements(blockElements, secondStorage)
          _      <- secondStorage.close()
        } yield result match {
          case (list, latestMessageHashes, latestMessages, topoSort, topoSortTail) => {
            list.foreach(_ shouldBe ((None, None, None, Set.empty, None, false)))
            latestMessageHashes shouldBe Map()
            latestMessages shouldBe Map()
            topoSort shouldBe Vector()
            topoSortTail shouldBe Vector()
          }
        }
      }
    }
  }
}
