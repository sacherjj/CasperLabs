package io.casperlabs.storage.dag

import java.nio.file.StandardOpenOption

import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.MessageSummary
import io.casperlabs.shared
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps._
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.block.{BlockStorage, FileLMDBIndexBlockStorage}
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.util.byteOps._
import io.casperlabs.storage.{
  ArbitraryStorageData,
  BlockMsgWithTransform,
  Context,
  SQLiteFixture,
  SQLiteStorage
}
import monix.eval.Task
import monix.execution.Scheduler
import org.scalacheck.Shrink
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.util.Random

trait DagStorageTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll
    with ArbitraryStorageData {
  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    dagSize = 5,
    dagDepth = 3,
    dagBranchingFactor = 1,
    maxSessionCodeBytes = 1,
    maxPaymentCodeBytes = 1,
    minSessionCodeBytes = 1,
    minPaymentCodeBytes = 1
  )

  /** Needed only for keeping compatibility with previous 'storage' module custom ScalaCheck generators
    * [[FileDagStorageTest]] fails if use plain generators
    * */
  def zeroedRank(b: BlockMsgWithTransform): BlockMsgWithTransform =
    b.withBlockMessage(b.getBlockMessage.withHeader(b.getBlockMessage.getHeader.copy(rank = 0)))

  def zeroedRanks(bs: List[BlockMsgWithTransform]): List[BlockMsgWithTransform] = bs.map(zeroedRank)

  val scheduler = Scheduler.fixedPool("dag-storage-test-scheduler", 4)

  def withDagStorage[R](f: DagStorage[Task] => Task[R]): R

  "DAG Storage" should "be able to lookup a stored block" in {
    def setRank(blockMsg: BlockMsgWithTransform, rank: Int): BlockMsgWithTransform =
      blockMsg.withBlockMessage(
        blockMsg.getBlockMessage.withHeader(
          blockMsg.getBlockMessage.getHeader.withRank(rank.toLong)
        )
      )

    forAll(genBlockMsgWithTransformDagFromGenesis) { initial =>
      val validatorsToBlocks = initial
        .groupBy(_.getBlockMessage.getHeader.validatorPublicKey)
        .mapValues(_.zipWithIndex.map { case (block, i) => setRank(block, i) })
      val latestBlocksByValidator = validatorsToBlocks.mapValues(_.maxBy(_.getBlockMessage.rank))
      val blockElements           = validatorsToBlocks.values.toList.flatten

      withDagStorage { dagStorage =>
        for {
          _ <- blockElements.traverse_(
                blockMsgWithTransform => dagStorage.insert(blockMsgWithTransform.getBlockMessage)
              )
          dag <- dagStorage.getRepresentation
          _ <- blockElements.traverse {
                case BlockMsgWithTransform(Some(b), _) =>
                  for {
                    blockSummary <- dag.lookup(b.blockHash)
                  } yield blockSummary shouldBe MessageSummary.fromBlock(b).toOption
                case _ => ???
              }
          _ <- latestBlocksByValidator.toList.traverse {
                case (validator, BlockMsgWithTransform(Some(b), _)) =>
                  for {
                    latestMessageHash <- dag.latestMessageHash(validator)
                    latestMessage     <- dag.latestMessage(validator)
                  } yield {
                    latestMessageHash shouldBe Some(b.blockHash)
                    latestMessage shouldBe MessageSummary.fromBlock(b).toOption
                  }
                case _ => ???
              }
          _ <- dag.latestMessageHashes.map { got =>
                got.toList should contain theSameElementsAs latestBlocksByValidator.toList.map {
                  case (v, b) => (v, b.getBlockMessage.blockHash)
                }
              }
          _ <- dag.latestMessages.map { got =>
                got.toList should contain theSameElementsAs latestBlocksByValidator.toList.map {
                  case (v, b) => (v, MessageSummary.fromBlock(b.getBlockMessage).get)
                }
              }
        } yield ()
      }
    }
  }

  "DAG Storage" should "be able to properly (de)serialize data" in {
    forAll { b: Block =>
      withDagStorage { storage =>
        val before = BlockSummary.fromBlock(b).toByteArray
        for {
          _                 <- storage.insert(b)
          dag               <- storage.getRepresentation
          messageSummaryOpt <- dag.lookup(b.blockHash)
          _ <- Task {
                messageSummaryOpt should not be None
                val got = messageSummaryOpt.get.blockSummary.toByteArray
                assert(before.sameElements(got))
              }
        } yield ()
      }
    }
  }
}

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

  private def defaultBlockSummaryLog(dagStorageDataDir: Path): Path =
    dagStorageDataDir.resolve("block-summary-log")

  private def defaultBlockSummaryCrc(dagStorageDataDir: Path): Path =
    dagStorageDataDir.resolve("block-summary-crc")

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
              //dag.lookup
              Option[MessageSummary],
              //dag.latestMessageHash
              Option[BlockHash],
              //dag.latestMessage
              Option[MessageSummary],
              //dag.children
              Set[BlockHash],
              //dag.justificationToBlocks
              Set[BlockHash],
              //dag.contains
              Boolean
          )
        ],
        //dag.latestMessageHashes
        Map[Validator, BlockHash],
        //dag.latestMessages
        Map[Validator, MessageSummary],
        //dag.topoSort
        Vector[Vector[BlockHash]],
        //dag.topoSortTail
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
                   blockSummary                     <- dag.lookup(b.blockHash)
                   latestMessageHash                <- dag.latestMessageHash(b.getHeader.validatorPublicKey)
                   latestMessage                    <- dag.latestMessage(b.getHeader.validatorPublicKey)
                   children                         <- dag.children(b.blockHash)
                   blocksWithSpecifiedJustification <- dag.justificationToBlocks(b.blockHash)
                   contains                         <- dag.contains(b.blockHash)
                 } yield (
                   blockSummary,
                   latestMessageHash,
                   latestMessage,
                   children,
                   blocksWithSpecifiedJustification,
                   contains
                 )
               case _ => ???
             }
      latestMessageHashes <- dag.latestMessageHashes
      latestMessages      <- dag.latestMessages
      topoSort            <- dag.topoSort(topoSortStartBlockNumber).compile.toVector
      topoSortTail        <- dag.topoSortTail(topoSortTailLength).compile.toVector
    } yield (list, latestMessageHashes, latestMessages, topoSort, topoSortTail)

  private def testLookupElementsResult(
      lookupResult: LookupResult,
      blockElements: List[Block],
      topoSortStartBlockNumber: Long = 0,
      topoSortTailLength: Int = 5
  ): Assertion = {
    val (list, latestMessageHashes, latestMessages, topoSort, topoSortTail) = lookupResult
    val realLatestMessages = blockElements.foldLeft(Map.empty[Validator, MessageSummary]) {
      case (lm, b) =>
        // Ignore empty sender for genesis block
        if (b.getHeader.validatorPublicKey != ByteString.EMPTY)
          lm.updated(b.getHeader.validatorPublicKey, MessageSummary.fromBlock(b).get)
        else
          lm
    }
    list.zip(blockElements).foreach {
      case (
          (
            blockSummary,
            latestMessageHash,
            latestMessage,
            children,
            blocksWithSpecifiedJustification,
            contains
          ),
          b
          ) =>
        blockSummary shouldBe MessageSummary.fromBlock(b).toOption
        latestMessageHash shouldBe realLatestMessages
          .get(b.getHeader.validatorPublicKey)
          .map(_.messageHash)
        latestMessage shouldBe realLatestMessages.get(b.getHeader.validatorPublicKey)
        children shouldBe
          blockElements
            .filter(_.getHeader.parentHashes.contains(b.blockHash))
            .map(_.blockHash)
            .toSet
        blocksWithSpecifiedJustification shouldBe
          blockElements
            .filter(_.getHeader.justifications.map(_.latestBlockHash).contains(b.blockHash))
            .map(_.blockHash)
            .toSet
        contains shouldBe true
    }
    latestMessageHashes shouldBe realLatestMessages.mapValues(_.messageHash)
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
    forAll(genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks)) { blockElements =>
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
    forAll(genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks)) { blockElements =>
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
    forAll(
      genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks),
      genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks)
    ) { (firstBlockElements, secondBlockElements) =>
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

  it should "be able to restore latest messages on startup with appended 64 garbage bytes" in {
    forAll(genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks)) { blockElements =>
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
    forAll(
      genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks),
      arbBlockMsgWithTransformFromBlock.arbitrary
    ) { (blockElements, garbageBlock) =>
      withDagStorageLocation { (dagStorageDataDir, blockStorage) =>
        for {
          firstStorage      <- createAtDefaultLocation(dagStorageDataDir)(blockStorage)
          _                 <- blockElements.traverse_(b => firstStorage.insert(b.getBlockMessage))
          _                 <- firstStorage.close()
          garbageByteString = BlockSummary.fromBlock(garbageBlock.getBlockMessage).toByteString
          garbageBytes      = garbageByteString.size.toByteString.concat(garbageByteString).toByteArray
          _ <- Sync[Task].delay {
                Files.write(
                  defaultBlockSummaryLog(dagStorageDataDir),
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
    forAll(genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks)) { blockElements =>
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
    forAll(genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks)) { blockElements =>
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
                  defaultBlockSummaryLog(dagStorageDataDir),
                  defaultCheckpointsDir(dagStorageDataDir).resolve("0-1")
                )
                Files.delete(defaultBlockSummaryCrc(dagStorageDataDir))
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
    forAll(genBlockMsgWithTransformDagFromGenesis.map(zeroedRanks)) { blockElements =>
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
            list.foreach(_ shouldBe ((None, None, None, Set.empty, Set.empty, false)))
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

class SQLiteDagStorageTest extends DagStorageTest with SQLiteFixture[DagStorage[Task]] {
  override def withDagStorage[R](f: DagStorage[Task] => Task[R]): R = runSQLiteTest[R](f)

  override def db: String = "/tmp/dag_storage.db"

  override def createTestResource: Task[DagStorage[Task]] =
    SQLiteStorage.create[Task](wrap = Task.pure)

  "SQLite DAG Storage" should "override validator's latest block hash only if rank is higher" in {
    forAll { (initial: Block, a: Block, c: Block) =>
      withDagStorage { storage =>
        def update(b: Block, validator: ByteString, rank: Long): Block =
          b.withHeader(b.getHeader.withValidatorPublicKey(validator).withRank(rank))

        val validator  = initial.validatorPublicKey
        val rank       = initial.rank
        val lowerRank  = update(a, validator, rank - 1)
        val higherRank = update(c, validator, rank + 1)

        val readLatestMessages = storage.getRepresentation.flatMap(
          dag =>
            (
              dag.latestMessageHashes,
              dag.latestMessages,
              dag.latestMessage(validator),
              dag.latestMessageHash(validator)
            ).mapN((_, _, _, _))
        )

        for {
          _ <- storage.insert(initial)
          (
            latestMessageHashesBefore,
            latestMessagesBefore,
            latestMessageBefore,
            latestMessageHashBefore
          ) <- readLatestMessages

          _ <- storage.insert(lowerRank)
          // block with lower rank should be ignored
          _ <- readLatestMessages.map {
                case (
                    latestMessageHashesGot,
                    latestMessagesGot,
                    latestMessageGot,
                    latestMessageHashGot
                    ) =>
                  latestMessageHashesGot shouldBe latestMessageHashesBefore
                  latestMessagesGot shouldBe latestMessagesBefore
                  latestMessageGot shouldBe latestMessageBefore
                  latestMessageHashGot shouldBe latestMessageHashBefore
              }

          // not checking new blocks with same rank because they should be invalidated
          // before storing because of equivocation

          _ <- storage.insert(higherRank)
          // only block with higher rank should override previous message
          _ <- readLatestMessages.map {
                case (
                    latestMessageHashesGot,
                    latestMessagesGot,
                    latestMessageGot,
                    latestMessageHashGot
                    ) =>
                  latestMessageHashesGot shouldBe Map(validator -> higherRank.blockHash)
                  latestMessagesGot shouldBe Map(
                    validator -> MessageSummary.fromBlock(higherRank).get
                  )
                  latestMessageGot shouldBe MessageSummary.fromBlock(higherRank).toOption
                  latestMessageHashGot shouldBe Some(higherRank.blockHash)
              }
        } yield ()
      }
    }
  }
}
