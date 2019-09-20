package io.casperlabs.storage.block

import cats._
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.protocol.{ApprovedBlock, ApprovedBlockCandidate}
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps._
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.block.InMemBlockStorage.emptyMapRef
import io.casperlabs.storage.{
  ArbitraryStorageData,
  BlockMsgWithTransform,
  Context,
  SQLiteFixture,
  SQLiteStorage
}
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalacheck._
import org.scalactic.anyvals.PosInt
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.language.higherKinds
import scala.util.Random

@silent("match may not be exhaustive")
trait BlockStorageTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with EitherValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll
    with ArbitraryStorageData {

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    maxSessionCodeBytes = 0,
    maxPaymentCodeBytes = 0
  )

  val blockElementsGen: Gen[List[BlockMsgWithTransform]] = listOfBlockMsgWithTransform(1, 3)
  val blockBatchesGen: Gen[List[List[BlockMsgWithTransform]]] = for {
    n       <- Gen.choose(1, 3)
    batches <- Gen.listOfN(n, blockElementsGen)
  } yield batches

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(100))

  def withStorage[R](f: BlockStorage[Task] => Task[R]): R

  def checkAllHashes(storage: BlockStorage[Task], hashes: List[BlockHash]) =
    hashes.traverse { h =>
      storage.getByPrefix(Base16.encode(h.toByteArray)).map(h -> _.isDefined)
    } map { res =>
      Inspectors.forAll(res) {
        case (_, isDefined) => isDefined shouldBe true
      }
    }

  it should "return Some(message) on get for a published key and return Some(blockSummary) on getSummary" in {
    forAll(blockElementsGen) { blockStorageElements =>
      withStorage { storage =>
        val items = blockStorageElements
        for {
          _ <- items.traverse_(storage.put)
          _ <- items.traverse[Task, Assertion] { block =>
                storage.get(block.getBlockMessage.blockHash).map(_ shouldBe Some(block)) *>
                  storage
                    .getBlockSummary(block.getBlockMessage.blockHash)
                    .map(
                      _ shouldBe Some(
                        block.toBlockSummary
                      )
                    )
              }
          _ <- checkAllHashes(storage, items.map(_.getBlockMessage.blockHash).toList)
        } yield ()
      }
    }
  }

  it should "discover blocks/summaries by block hash prefix" in {
    forAll(blockElementsGen) { blockStorageElements =>
      withStorage { storage =>
        val items = blockStorageElements
        for {
          _ <- items.traverse_(storage.put)
          _ <- items.traverse[Task, Unit] { blockMsg =>
                val randomPrefix =
                  Base16.encode(
                    blockMsg.getBlockMessage.blockHash.toByteArray.take(Random.nextInt(32) + 1)
                  )

                for {
                  _ <- storage
                        .getByPrefix(randomPrefix)
                        .map { maybeBlock =>
                          maybeBlock should not be empty
                          assert(
                            maybeBlock.get.getBlockMessage.blockHash
                              .startsWith(ByteString.copyFrom(Base16.decode(randomPrefix)))
                          )
                        }
                  _ <- storage.getSummaryByPrefix(randomPrefix).map { maybeSummary =>
                        maybeSummary should not be empty
                        assert(
                          maybeSummary.get.blockHash
                            .startsWith(ByteString.copyFrom(Base16.decode(randomPrefix)))
                        )
                      }
                } yield ()
              }
        } yield ()
      }
    }
  }

  it should "be able to get blocks containing the specific deploy" in {
    forAll(blockElementsGen) { blockStorageElements =>
      val deployHashToBlockHashes =
        blockStorageElements
          .flatMap(
            b =>
              b.getBlockMessage.getBody.deploys
                .map(
                  _.getDeploy.deployHash -> b.getBlockMessage.blockHash
                )
                .toSet
          )
          .groupBy(_._1)
          .mapValues(_.map(_._2))

      withStorage { storage =>
        val items = blockStorageElements
        for {
          _ <- items.traverse_(storage.put)
          _ <- items.traverse[Task, Seq[Assertion]] { block =>
                block.getBlockMessage.getBody.deploys.toList.traverse { deploy =>
                  storage
                    .findBlockHashesWithDeployhash(deploy.getDeploy.deployHash)
                    .map(
                      _ should contain theSameElementsAs (
                        deployHashToBlockHashes(deploy.getDeploy.deployHash)
                      )
                    )
                }
              }
        } yield ()
      }
    }
  }

  it should "be able to properly (de)serialize data" in {
    forAll { b: BlockMsgWithTransform =>
      withStorage { storage =>
        val before = b.toByteArray
        for {
          _          <- storage.put(b)
          maybeBlock <- storage.get(b.getBlockMessage.blockHash)
          _ <- Task {
                maybeBlock should not be None
                val got = maybeBlock.get.toByteArray
                assert(before.sameElements(got))
              }
        } yield ()
      }
    }
  }

  //TODO: update this test to properly test rollback feature.
  //https://casperlabs.atlassian.net/browse/STOR-95
  it should "rollback the transaction on error" ignore {
    withStorage { storage =>
      val exception = new RuntimeException("msg")

      def elem: (BlockHash, BlockMsgWithTransform) =
        throw exception

      for {
        _                  <- storage.isEmpty.map(_ shouldBe true)
        (blockHash, block) = elem
        putAttempt         <- storage.put(blockHash, block).attempt
        _                  = putAttempt.left.value shouldBe exception
        _                  <- storage.isEmpty.map(_ shouldBe true)
      } yield ()
    }
  }
}

@silent("match may not be exhaustive")
class InMemBlockStorageTest extends BlockStorageTest {
  override def withStorage[R](f: BlockStorage[Task] => Task[R]): R = {
    val test = for {
      refTask             <- emptyMapRef[Task, (BlockMsgWithTransform, BlockSummary)]
      deployHashesRefTask <- emptyMapRef[Task, Seq[BlockHash]]
      approvedBlockRef    <- Ref[Task].of(none[ApprovedBlock])
      metrics             = new MetricsNOP[Task]()
      storage = InMemBlockStorage
        .create[Task](Monad[Task], refTask, deployHashesRefTask, approvedBlockRef, metrics)
      _      <- storage.isEmpty.map(_ shouldBe true)
      result <- f(storage)
    } yield result
    test.unsafeRunSync
  }
}

@silent("match may not be exhaustive")
class LMDBBlockStorageTest extends BlockStorageTest {

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("block-storage-test-")
  private[this] val mapSize: Long    = 100L * 1024L * 1024L * 4096L

  override def withStorage[R](f: BlockStorage[Task] => Task[R]): R = {
    val dbDir                           = mkTmpDir()
    val env                             = Context.env(dbDir, mapSize)
    implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
    val storage                         = LMDBBlockStorage.create[Task](env)
    val test = for {
      _      <- storage.isEmpty.map(_ shouldBe true)
      result <- f(storage)
    } yield result
    try {
      test.unsafeRunSync
    } finally {
      env.close()
      dbDir.recursivelyDelete()
    }
  }
}

@silent("match may not be exhaustive")
class FileLMDBIndexBlockStorageTest extends BlockStorageTest {
  val scheduler = Scheduler.fixedPool("block-storage-test-scheduler", 4)

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("block-storage-test-")
  private[this] val mapSize: Long    = 100L * 1024L * 1024L * 4096L

  override def withStorage[R](f: BlockStorage[Task] => Task[R]): R = {
    val dbDir                           = mkTmpDir()
    implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
    implicit val log: Log[Task]         = new Log.NOPLog[Task]()
    val env                             = Context.env(dbDir, mapSize)
    val test = for {
      storage <- FileLMDBIndexBlockStorage.create[Task](env, dbDir).map(_.right.get)
      _       <- storage.isEmpty.map(_ shouldBe true)
      result  <- f(storage)
    } yield result
    try {
      test.unsafeRunSync
    } finally {
      env.close()
      dbDir.recursivelyDelete()
    }
  }

  private def createBlockStorage(blockStorageDataDir: Path): Task[BlockStorage[Task]] = {
    implicit val metrics = new MetricsNOP[Task]()
    implicit val log     = new Log.NOPLog[Task]()
    val env              = Context.env(blockStorageDataDir, 100L * 1024L * 1024L * 4096L)
    FileLMDBIndexBlockStorage.create[Task](env, blockStorageDataDir).map(_.right.get)
  }

  def withStorageLocation[R](f: Path => Task[R]): R = {
    val testProgram = Sync[Task].bracket {
      Sync[Task].delay {
        mkTmpDir()
      }
    } { blockStorageDataDir =>
      f(blockStorageDataDir)
    } { blockStorageDataDir =>
      Sync[Task].delay {
        blockStorageDataDir.recursivelyDelete()
      }
    }
    testProgram.unsafeRunSync(scheduler)
  }

  "FileLMDBIndexBlockStorage" should "persist storage on restart" in {
    forAll(blockElementsGen) { blockStorageElements =>
      withStorageLocation { blockStorageDataDir =>
        for {
          firstStorage  <- createBlockStorage(blockStorageDataDir)
          _             <- blockStorageElements.traverse_[Task, Unit](firstStorage.put)
          _             <- firstStorage.close()
          secondStorage <- createBlockStorage(blockStorageDataDir)
          _ <- blockStorageElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStorage.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStorage,
                blockStorageElements.map(_.getBlockMessage.blockHash).toList
              )
          _ <- secondStorage.close()
        } yield ()
      }
    }
  }

  "FileLMDBIndexBlockStorage" should "persist approved block on restart" in {
    import io.casperlabs.casper.protocol.{BlockMessage, Signature}
    withStorageLocation { blockStorageDataDir =>
      val approvedBlock =
        ApprovedBlock(
          Some(ApprovedBlockCandidate(Some(BlockMessage()), 1)),
          List(Signature(ByteString.EMPTY, "", ByteString.EMPTY))
        )

      for {
        firstStorage        <- createBlockStorage(blockStorageDataDir)
        _                   <- firstStorage.putApprovedBlock(approvedBlock)
        _                   <- firstStorage.close()
        secondStorage       <- createBlockStorage(blockStorageDataDir)
        storedApprovedBlock <- secondStorage.getApprovedBlock()
        _ = storedApprovedBlock shouldBe Some(
          approvedBlock
        )
        _ <- secondStorage.close()
      } yield ()
    }
  }

  it should "persist storage after checkpoint" in {
    forAll(blockElementsGen, minSize(10), sizeRange(10)) { blockStorageElements =>
      withStorageLocation { blockStorageDataDir =>
        val (firstHalf, secondHalf) = blockStorageElements.splitAt(blockStorageElements.size / 2)
        for {
          firstStorage <- createBlockStorage(blockStorageDataDir)
          _            <- firstHalf.traverse_[Task, Unit](firstStorage.put)
          _            <- firstStorage.checkpoint()
          _            <- secondHalf.traverse_[Task, Unit](firstStorage.put)
          _ <- blockStorageElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStorage.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                firstStorage,
                blockStorageElements.map(_.getBlockMessage.blockHash).toList
              )
          _             <- firstStorage.close()
          secondStorage <- createBlockStorage(blockStorageDataDir)
          _ <- blockStorageElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStorage.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStorage,
                blockStorageElements.map(_.getBlockMessage.blockHash).toList
              )
          _ <- secondStorage.close()
        } yield ()
      }
    }
  }

  it should "be able to store multiple checkpoints" in {
    forAll(blockBatchesGen) { blockStorageBatches =>
      withStorageLocation { blockStorageDataDir =>
        val blocks = blockStorageBatches.flatten
        for {
          firstStorage <- createBlockStorage(blockStorageDataDir)
          _ <- blockStorageBatches.traverse_[Task, Unit](
                blockStorageElements =>
                  blockStorageElements
                    .traverse_[Task, Unit](firstStorage.put) *> firstStorage.checkpoint()
              )
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStorage.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                firstStorage,
                blocks.map(_.getBlockMessage.blockHash).toList
              )
          _             <- firstStorage.close()
          secondStorage <- createBlockStorage(blockStorageDataDir)
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStorage.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStorage,
                blocks.map(_.getBlockMessage.blockHash).toList
              )
          _ <- secondStorage.close()
        } yield ()
      }
    }
  }

  it should "be able to clean storage and continue to work" in {
    forAll(blockBatchesGen) { blockStorageBatches =>
      withStorageLocation { blockStorageDataDir =>
        val blocks            = blockStorageBatches.flatten
        val checkpointsDir    = blockStorageDataDir.resolve("checkpoints")
        val approvedBlockPath = blockStorageDataDir.resolve("approved-block")
        for {
          firstStorage <- createBlockStorage(blockStorageDataDir)
          _ <- blockStorageBatches.traverse_[Task, Unit](
                blockStorageElements =>
                  blockStorageElements
                    .traverse_[Task, Unit](firstStorage.put) *> firstStorage.checkpoint()
              )
          _ = checkpointsDir.toFile.list().size shouldBe blockStorageBatches.size
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStorage.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- firstStorage.isEmpty.map(_ shouldBe blocks.isEmpty)
          _ = approvedBlockPath.toFile.exists() shouldBe true
          _ <- firstStorage.clear()
          _ = approvedBlockPath.toFile.exists() shouldBe false
          _ = checkpointsDir.toFile.list().size shouldBe 0
          _ <- firstStorage.isEmpty.map(_ shouldBe true)
          _ <- blockStorageBatches.traverse_[Task, Unit](
                blockStorageElements =>
                  blockStorageElements
                    .traverse_[Task, Unit](firstStorage.put) *> firstStorage.checkpoint()
              )
          _             = checkpointsDir.toFile.list().size shouldBe blockStorageBatches.size
          _             <- firstStorage.close()
          secondStorage <- createBlockStorage(blockStorageDataDir)
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStorage.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStorage,
                blocks.map(_.getBlockMessage.blockHash).toList
              )
        } yield ()
      }
    }
  }
}

class SQLiteBlockStorageTest extends BlockStorageTest with SQLiteFixture[BlockStorage[Task]] {
  override def withStorage[R](f: BlockStorage[Task] => Task[R]): R = runSQLiteTest[R](f)

  override def db: String = "/tmp/block_storage.db"

  override def createTestResource: Task[BlockStorage[Task]] =
    SQLiteStorage.create[Task]()
}
