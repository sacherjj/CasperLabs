package io.casperlabs.blockstorage

import cats._
import cats.effect.Sync
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStorage.{BlockHash, DeployHash}
import io.casperlabs.blockstorage.InMemBlockStorage.emptyMapRef
import io.casperlabs.blockstorage.blockImplicits.{blockBatchesGen, blockElementsGen}
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.protocol.{ApprovedBlock, ApprovedBlockCandidate}
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.ipc._
import io.casperlabs.casper.consensus.state.{Unit => SUnit, _}
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps._
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalactic.anyvals.PosInt
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.language.higherKinds

@silent("match may not be exhaustive")
trait BlockStorageTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with EitherValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(100))

  private[this] def toBlockMessage(bh: BlockHash, v: Long, ts: Long): Block =
    Block()
      .withBlockHash(bh)
      .withHeader(Block.Header().withProtocolVersion(v).withTimestamp(ts))

  def withStorage[R](f: BlockStorage[Task] => Task[R]): R

  def checkAllHashes(storage: BlockStorage[Task], hashes: List[BlockHash]) =
    hashes.traverse { h =>
      storage.findBlockHash(_ == h).map(h -> _.isDefined)
    } map { res =>
      Inspectors.forAll(res) {
        case (_, isDefined) => isDefined shouldBe true
      }
    }

  "Block Storage" should "return Some(message) on get for a published key and return Some(blockSummary) on getSummary" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStorageElements =>
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

  it should "discover keys by predicate" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStorageElements =>
      withStorage { storage =>
        val items = blockStorageElements
        for {
          _ <- items.traverse_(storage.put)
          _ <- items.traverse[Task, Assertion] { block =>
                storage
                  .findBlockHash(
                    _ == ByteString.copyFrom(block.getBlockMessage.blockHash.toByteArray)
                  )
                  .map { w =>
                    w should not be empty
                    w.get shouldBe block.getBlockMessage.blockHash
                  }
              }
        } yield ()
      }
    }
  }

  it should "overwrite existing value" in
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStorageElements =>
      withStorage { storage =>
        val items = blockStorageElements.map {
          case BlockMsgWithTransform(Some(block), transform) =>
            val newBlock = toBlockMessage(block.blockHash, 200L, 20000L)
            (
              block.blockHash,
              BlockMsgWithTransform(Some(block), transform),
              BlockMsgWithTransform(Some(newBlock), transform)
            )
        }
        for {
          _ <- items.traverse_[Task, Unit] { case (k, v1, _) => storage.put(k, v1) }
          _ <- items.traverse_[Task, Assertion] {
                case (k, v1, _) => {
                  storage.get(k).map(_ shouldBe Some(v1))
                  storage
                    .getBlockSummary(k)
                    .map(_ shouldBe Some(v1.toBlockSummary))
                }
              }
          _ <- items.traverse_[Task, Unit] { case (k, _, v2) => storage.put(k, v2) }
          _ <- items.traverse_[Task, Assertion] {
                case (k, _, v2) =>
                  storage.get(k).map(_ shouldBe Some(v2))
                  storage
                    .getBlockSummary(k)
                    .map(_ shouldBe Some(v2.toBlockSummary))
              }
          _ <- checkAllHashes(storage, items.map(_._1).toList)
        } yield ()
      }
    }

  it should "be able to get blocks containing the specific deploy" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStorageElements =>
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

  //TODO: update this test to properly test rollback feature.
  //https://casperlabs.atlassian.net/browse/STOR-95
  it should "rollback the transaction on error" ignore {
    withStorage { storage =>
      val exception = new RuntimeException("msg")

      def elem: (BlockHash, BlockMsgWithTransform) =
        throw exception

      for {
        _                  <- storage.findBlockHash(_ => true).map(_ shouldBe empty)
        (blockHash, block) = elem
        putAttempt         <- storage.put(blockHash, block).attempt
        _                  = putAttempt.left.value shouldBe exception
        result             <- storage.findBlockHash(_ => true).map(_ shouldBe empty)
      } yield result
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
      _      <- storage.findBlockHash(_ => true).map(x => assert(x.isEmpty))
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
      _      <- storage.findBlockHash(_ => true).map(x => assert(x.isEmpty))
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
      _       <- storage.findBlockHash(_ => true).map(x => assert(x.isEmpty))
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
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStorageElements =>
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
    forAll(blockBatchesGen, minSize(5), sizeRange(10)) { blockStorageBatches =>
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
    forAll(blockBatchesGen, minSize(5), sizeRange(10)) { blockStorageBatches =>
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
          _ <- firstStorage.findBlockHash(_ => true).map(_.isEmpty shouldBe blocks.isEmpty)
          _ = approvedBlockPath.toFile.exists() shouldBe true
          _ <- firstStorage.clear()
          _ = approvedBlockPath.toFile.exists() shouldBe false
          _ = checkpointsDir.toFile.list().size shouldBe 0
          _ <- firstStorage.findBlockHash(_ => true).map(_ shouldBe empty)
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
