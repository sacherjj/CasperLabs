package io.casperlabs.blockstorage

import cats._
import cats.effect.Sync
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.{BlockHash, DeployHash}
import io.casperlabs.blockstorage.InMemBlockStore.emptyMapRef
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

trait BlockStoreTest
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

  def withStore[R](f: BlockStore[Task] => Task[R]): R

  def checkAllHashes(store: BlockStore[Task], hashes: List[BlockHash]) =
    hashes.traverse { h =>
      store.findBlockHash(_ == h).map(h -> _.isDefined)
    } map { res =>
      Inspectors.forAll(res) {
        case (hash, isDefined) => isDefined shouldBe true
      }
    }

  "Block Store" should "return Some(message) on get for a published key and return Some(blockSummary) on getSummary" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStore { store =>
        val items = blockStoreElements
        for {
          _ <- items.traverse_(store.put)
          _ <- items.traverse[Task, Assertion] { block =>
                store.get(block.getBlockMessage.blockHash).map(_ shouldBe Some(block)) *>
                  store
                    .getBlockSummary(block.getBlockMessage.blockHash)
                    .map(
                      _ shouldBe Some(
                        block.toBlockSummary
                      )
                    )
              }
          _ <- checkAllHashes(store, items.map(_.getBlockMessage.blockHash).toList)
        } yield ()
      }
    }
  }

  it should "discover keys by predicate" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStore { store =>
        val items = blockStoreElements
        for {
          _ <- items.traverse_(store.put)
          _ <- items.traverse[Task, Assertion] { block =>
                store
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
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStore { store =>
        val items = blockStoreElements.map {
          case BlockMsgWithTransform(Some(block), transform) =>
            val newBlock = toBlockMessage(block.blockHash, 200L, 20000L)
            (
              block.blockHash,
              BlockMsgWithTransform(Some(block), transform),
              BlockMsgWithTransform(Some(newBlock), transform)
            )
        }
        for {
          _ <- items.traverse_[Task, Unit] { case (k, v1, _) => store.put(k, v1) }
          _ <- items.traverse_[Task, Assertion] {
                case (k, v1, _) => {
                  store.get(k).map(_ shouldBe Some(v1))
                  store
                    .getBlockSummary(k)
                    .map(_ shouldBe Some(v1.toBlockSummary))
                }
              }
          _ <- items.traverse_[Task, Unit] { case (k, _, v2) => store.put(k, v2) }
          _ <- items.traverse_[Task, Assertion] {
                case (k, _, v2) =>
                  store.get(k).map(_ shouldBe Some(v2))
                  store
                    .getBlockSummary(k)
                    .map(_ shouldBe Some(v2.toBlockSummary))
              }
          _ <- checkAllHashes(store, items.map(_._1).toList)
        } yield ()
      }
    }

  it should "be able to get blocks containing the specific deploy" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      val deployHashToBlockHashes =
        blockStoreElements
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

      withStore { store =>
        val items = blockStoreElements
        for {
          _ <- items.traverse_(store.put)
          _ <- items.traverse[Task, Seq[Assertion]] { block =>
                block.getBlockMessage.getBody.deploys.toList.traverse { deploy =>
                  store
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
    withStore { store =>
      val exception = new RuntimeException("msg")

      def elem: (BlockHash, BlockMsgWithTransform) =
        throw exception

      for {
        _                  <- store.findBlockHash(_ => true).map(_ shouldBe empty)
        (blockHash, block) = elem
        putAttempt         <- store.put(blockHash, block).attempt
        _                  = putAttempt.left.value shouldBe exception
        result             <- store.findBlockHash(_ => true).map(_ shouldBe empty)
      } yield result
    }
  }
}

class InMemBlockStoreTest extends BlockStoreTest {
  override def withStore[R](f: BlockStore[Task] => Task[R]): R = {
    val test = for {
      refTask             <- emptyMapRef[Task, (BlockMsgWithTransform, BlockSummary)]
      deployHashesRefTask <- emptyMapRef[Task, Seq[BlockHash]]
      approvedBlockRef    <- Ref[Task].of(none[ApprovedBlock])
      metrics             = new MetricsNOP[Task]()
      lock                <- Semaphore[Task](1)
      store = InMemBlockStore
        .create[Task](Monad[Task], refTask, deployHashesRefTask, approvedBlockRef, metrics)
      _      <- store.findBlockHash(_ => true).map(x => assert(x.isEmpty))
      result <- f(store)
    } yield result
    test.unsafeRunSync
  }
}

class LMDBBlockStoreTest extends BlockStoreTest {

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("block-store-test-")
  private[this] val mapSize: Long    = 100L * 1024L * 1024L * 4096L

  override def withStore[R](f: BlockStore[Task] => Task[R]): R = {
    val dbDir                           = mkTmpDir()
    val env                             = Context.env(dbDir, mapSize)
    implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
    val store                           = LMDBBlockStore.create[Task](env, dbDir)
    val test = for {
      _      <- store.findBlockHash(_ => true).map(x => assert(x.isEmpty))
      result <- f(store)
    } yield result
    try {
      test.unsafeRunSync
    } finally {
      env.close()
      dbDir.recursivelyDelete()
    }
  }
}

class FileLMDBIndexBlockStoreTest extends BlockStoreTest {
  val scheduler = Scheduler.fixedPool("block-storage-test-scheduler", 4)

  import java.nio.file.{Files, Path}

  private[this] def mkTmpDir(): Path = Files.createTempDirectory("block-store-test-")
  private[this] val mapSize: Long    = 100L * 1024L * 1024L * 4096L

  override def withStore[R](f: BlockStore[Task] => Task[R]): R = {
    val dbDir                           = mkTmpDir()
    implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
    implicit val log: Log[Task]         = new Log.NOPLog[Task]()
    val env                             = Context.env(dbDir, mapSize)
    val test = for {
      store  <- FileLMDBIndexBlockStore.create[Task](env, dbDir).map(_.right.get)
      _      <- store.findBlockHash(_ => true).map(x => assert(x.isEmpty))
      result <- f(store)
    } yield result
    try {
      test.unsafeRunSync
    } finally {
      env.close()
      dbDir.recursivelyDelete()
    }
  }

  private def createBlockStore(blockStoreDataDir: Path): Task[BlockStore[Task]] = {
    implicit val metrics = new MetricsNOP[Task]()
    implicit val log     = new Log.NOPLog[Task]()
    val env              = Context.env(blockStoreDataDir, 100L * 1024L * 1024L * 4096L)
    FileLMDBIndexBlockStore.create[Task](env, blockStoreDataDir).map(_.right.get)
  }

  def withStoreLocation[R](f: Path => Task[R]): R = {
    val testProgram = Sync[Task].bracket {
      Sync[Task].delay {
        mkTmpDir()
      }
    } { blockStoreDataDir =>
      f(blockStoreDataDir)
    } { blockStoreDataDir =>
      Sync[Task].delay {
        blockStoreDataDir.recursivelyDelete()
      }
    }
    testProgram.unsafeRunSync(scheduler)
  }

  "FileLMDBIndexBlockStore" should "persist storage on restart" in {
    forAll(blockElementsGen, minSize(0), sizeRange(10)) { blockStoreElements =>
      withStoreLocation { blockStoreDataDir =>
        for {
          firstStore  <- createBlockStore(blockStoreDataDir)
          _           <- blockStoreElements.traverse_[Task, Unit](firstStore.put)
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStore,
                blockStoreElements.map(_.getBlockMessage.blockHash).toList
              )
          _ <- secondStore.close()
        } yield ()
      }
    }
  }

  "FileLMDBIndexBlockStore" should "persist approved block on restart" in {
    import io.casperlabs.casper.protocol.{BlockMessage, Signature}
    withStoreLocation { blockStoreDataDir =>
      val approvedBlock =
        ApprovedBlock(
          Some(ApprovedBlockCandidate(Some(BlockMessage()), 1)),
          List(Signature(ByteString.EMPTY, "", ByteString.EMPTY))
        )
      val key            = Key(Key.Value.Hash(Key.Hash()))
      val transform      = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
      val transforEntrys = Seq(TransformEntry(Some(key), Some(transform)))

      for {
        firstStore          <- createBlockStore(blockStoreDataDir)
        _                   <- firstStore.putApprovedBlock(approvedBlock)
        _                   <- firstStore.close()
        secondStore         <- createBlockStore(blockStoreDataDir)
        storedApprovedBlock <- secondStore.getApprovedBlock
        _ = storedApprovedBlock shouldBe Some(
          approvedBlock
        )
        _ <- secondStore.close()
      } yield ()
    }
  }

  it should "persist storage after checkpoint" in {
    forAll(blockElementsGen, minSize(10), sizeRange(10)) { blockStoreElements =>
      withStoreLocation { blockStoreDataDir =>
        val (firstHalf, secondHalf) = blockStoreElements.splitAt(blockStoreElements.size / 2)
        for {
          firstStore <- createBlockStore(blockStoreDataDir)
          _          <- firstHalf.traverse_[Task, Unit](firstStore.put)
          _          <- firstStore.checkpoint()
          _          <- secondHalf.traverse_[Task, Unit](firstStore.put)
          _ <- blockStoreElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                firstStore,
                blockStoreElements.map(_.getBlockMessage.blockHash).toList
              )
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreElements.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStore,
                blockStoreElements.map(_.getBlockMessage.blockHash).toList
              )
          _ <- secondStore.close()
        } yield ()
      }
    }
  }

  it should "be able to store multiple checkpoints" in {
    forAll(blockBatchesGen, minSize(5), sizeRange(10)) { blockStoreBatches =>
      withStoreLocation { blockStoreDataDir =>
        val blocks = blockStoreBatches.flatten
        for {
          firstStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreBatches.traverse_[Task, Unit](
                blockStoreElements =>
                  blockStoreElements
                    .traverse_[Task, Unit](firstStore.put) *> firstStore.checkpoint()
              )
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                firstStore,
                blocks.map(_.getBlockMessage.blockHash).toList
              )
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStore,
                blocks.map(_.getBlockMessage.blockHash).toList
              )
          _ <- secondStore.close()
        } yield ()
      }
    }
  }

  it should "be able to clean storage and continue to work" in {
    forAll(blockBatchesGen, minSize(5), sizeRange(10)) { blockStoreBatches =>
      withStoreLocation { blockStoreDataDir =>
        val blocks            = blockStoreBatches.flatten
        val checkpointsDir    = blockStoreDataDir.resolve("checkpoints")
        val approvedBlockPath = blockStoreDataDir.resolve("approved-block")
        for {
          firstStore <- createBlockStore(blockStoreDataDir)
          _ <- blockStoreBatches.traverse_[Task, Unit](
                blockStoreElements =>
                  blockStoreElements
                    .traverse_[Task, Unit](firstStore.put) *> firstStore.checkpoint()
              )
          _ = checkpointsDir.toFile.list().size shouldBe blockStoreBatches.size
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  firstStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- firstStore.findBlockHash(_ => true).map(_.isEmpty shouldBe blocks.isEmpty)
          _ = approvedBlockPath.toFile.exists() shouldBe true
          _ <- firstStore.clear()
          _ = approvedBlockPath.toFile.exists() shouldBe false
          _ = checkpointsDir.toFile.list().size shouldBe 0
          _ <- firstStore.findBlockHash(_ => true).map(_ shouldBe empty)
          _ <- blockStoreBatches.traverse_[Task, Unit](
                blockStoreElements =>
                  blockStoreElements
                    .traverse_[Task, Unit](firstStore.put) *> firstStore.checkpoint()
              )
          _           = checkpointsDir.toFile.list().size shouldBe blockStoreBatches.size
          _           <- firstStore.close()
          secondStore <- createBlockStore(blockStoreDataDir)
          _ <- blocks.traverse[Task, Assertion] {
                case b @ BlockMsgWithTransform(Some(block), _) =>
                  secondStore.get(block.blockHash).map(_ shouldBe Some(b))
              }
          _ <- checkAllHashes(
                secondStore,
                blocks.map(_.getBlockMessage.blockHash).toList
              )
        } yield ()
      }
    }
  }
}
