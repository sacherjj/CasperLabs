package io.casperlabs.storage.block

import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie.util.transactor.Transactor
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.block.BlockStorage.{BlockHash, DeployHash}
import io.casperlabs.storage.block.CachingBlockStorageTest.{
  createSQLiteBlockStorage,
  CachingBlockStorageTestData,
  MockMetrics
}
import io.casperlabs.storage.dag.SQLiteDagStorage
import io.casperlabs.storage.deploy.SQLiteDeployStorage
import io.casperlabs.storage.{ArbitraryStorageData, BlockMsgWithTransform, SQLiteFixture}
import monix.eval.Task
import org.scalatest._

import scala.concurrent.duration._

class CachingBlockStorageTest
    extends WordSpecLike
    with Matchers
    with ArbitraryStorageData
    with SQLiteFixture[CachingBlockStorageTestData] {
  import BlockStorage.BlockHash

  override def db: String = "/tmp/caching_block_storage_test.db"

  private def prepareTestEnvironment(cacheSize: Long) = {
    implicit val metrics: MockMetrics = new MockMetrics()
    for {
      blockStorage <- createSQLiteBlockStorage
      cache        <- CachingBlockStorage[Task](blockStorage, cacheSize)
    } yield CachingBlockStorageTestData(
      underlying = blockStorage,
      cache = cache,
      metrics = metrics
    )
  }

  override def createTestResource: Task[CachingBlockStorageTestData] =
    prepareTestEnvironment(cacheSize = 1024L * 1024L * 25L)

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    maxSessionCodeBytes = 1,
    maxPaymentCodeBytes = 1,
    minSessionCodeBytes = 1,
    minPaymentCodeBytes = 1
  )

  val sampleBlock = sample(arbBlockMsgWithTransformFromBlock.arbitrary)

  def verifyCached[A](
      name: String,
      expected: A
  )(get: BlockStorage[Task] => BlockHash => Task[A]): Unit =
    verifyCached[A](names = List(name), expected)(get)

  def verifyCached[A](
      names: List[String],
      expected: A
  )(
      get: BlockStorage[Task] => BlockHash => Task[A]
  ): Unit = {
    // Store it through cache
    runSQLiteTest {
      case CachingBlockStorageTestData(_, cache, metrics) =>
        for {
          _        <- cache.put(sampleBlock)
          thing    <- get(cache)(sampleBlock.getBlockMessage.blockHash)
          _        = thing shouldBe expected
          counters <- metrics.counterRef.get
          _        = names.map(counters.getOrElse(_, 0L)).sum shouldBe 1
        } yield ()
    }
    // Store it through underlying
    runSQLiteTest {
      case CachingBlockStorageTestData(underlying, cache, metrics) =>
        for {
          _        <- underlying.put(sampleBlock)
          thing    <- get(cache)(sampleBlock.getBlockMessage.blockHash)
          _        = thing shouldBe expected
          counters <- metrics.counterRef.get
          _        = names.map(counters.getOrElse(_, 0L)).sum shouldBe 2
        } yield ()
    }
  }

  "CachingBlockStorage" when {
    "a block is not in the cache" should {
      "get it from the underlying store and not cache it" in {
        runSQLiteTest {
          case CachingBlockStorageTestData(underlying, cache, metrics) =>
            for {
              _        <- underlying.put(sampleBlock)
              block    <- cache.get(sampleBlock.getBlockMessage.blockHash)
              _        = block shouldBe Some(sampleBlock)
              _        <- cache.get(sampleBlock.getBlockMessage.blockHash)
              counters <- metrics.counterRef.get
              _        = counters("get") shouldBe (2 + 2)
            } yield ()
        }
      }
    }

    "a block is added to the store" should {
      "cache it" in {
        runSQLiteTest {
          case CachingBlockStorageTestData(_, cache, metrics) =>
            for {
              _        <- cache.put(sampleBlock)
              blockC   <- cache.get(sampleBlock.getBlockMessage.blockHash)
              _        = blockC shouldBe Some(sampleBlock)
              counters <- metrics.counterRef.get
              _        = counters("put") shouldBe 2
              _        = counters("get") shouldBe 1
              blockU   <- cache.get(sampleBlock.getBlockMessage.blockHash)
              _        = blockU shouldBe Some(sampleBlock)
            } yield ()
        }
      }

      "evict older items" in {
        val otherBlocks = List.range(0, 100) map { i =>
          sampleBlock.withBlockMessage(
            sampleBlock.getBlockMessage.copy(
              blockHash = ByteString.copyFromUtf8(i.toString)
            )
          )
        }
        runSQLiteTest(
          resources = prepareTestEnvironment(cacheSize = sampleBlock.toByteArray.length.toLong * 10),
          test = {
            case CachingBlockStorageTestData(_, cache, metrics) =>
              for {
                _        <- cache.put(sampleBlock)
                _        <- cache.get(sampleBlock.getBlockMessage.blockHash)
                _        <- otherBlocks.traverse(cache.put(_))
                _        <- cache.get(sampleBlock.getBlockMessage.blockHash)
                _        <- cache.get(otherBlocks.last.getBlockMessage.blockHash)
                counters <- metrics.counterRef.get
                _        = counters("get") shouldBe (1 + 2 + 1)
              } yield ()
          },
          timeout = 15.seconds
        )
      }
    }
  }

  "CachingBlockStorage" should {
    "cache `contains`" in {
      // 'contains' uses 'get' internally in BlockStorage
      verifyCached("contains" :: "get" :: Nil, true) { store =>
        store.contains(_)
      }
    }
    "cache `get`" in {
      verifyCached("get", Option(sampleBlock.getBlockMessage.blockHash)) { store => hash =>
        store.get(hash).map(_.map(_.getBlockMessage.blockHash))
      }
    }
    "cache `getBlockSummary`" in {
      val summary = BlockSummary(
        sampleBlock.getBlockMessage.blockHash,
        sampleBlock.getBlockMessage.header,
        sampleBlock.getBlockMessage.signature
      )
      verifyCached("getBlockSummary", Option(summary)) { store =>
        store.getBlockSummary
      }
    }
  }
}

object CachingBlockStorageTest {
  // Using the metrics added by MeteredBlockStorage
  class MockMetrics() extends Metrics.MetricsNOP[Task] {
    val counterRef = Ref.unsafe[Task, Map[String, Long]](Map.empty)

    override def incrementCounter(name: String, delta: Long = 1)(
        implicit ev: Metrics.Source
    ): Task[Unit] =
      counterRef.update {
        _ |+| Map(name -> delta)
      }
  }

  case class CachingBlockStorageTestData(
      underlying: BlockStorage[Task],
      cache: BlockStorage[Task],
      metrics: MockMetrics
  )

  /**
    * We can't use SQLiteBlockStorage directly without SQLite(Dag and Deploy)Storage
    * Because of dependencies between tables.
    *
    * We can't use SQLiteStorage because it delegates some of BlockStorage methods (e.g. contains)
    * to dagStorage instead of blockStorage breaking tests
    */
  def createSQLiteBlockStorage(
      implicit metrics: Metrics[Task],
      xa: Transactor[Task]
  ): Task[BlockStorage[Task]] =
    for {
      underlyingBlockStorage <- SQLiteBlockStorage.create[Task](xa, xa)
      dagStorage             <- SQLiteDagStorage.create[Task](xa, xa)
      deployStorage          <- SQLiteDeployStorage.create[Task](100, xa, xa)
    } yield new BlockStorage[Task] {
      override def get(blockHash: BlockHash): Task[Option[BlockMsgWithTransform]] =
        underlyingBlockStorage.get(blockHash)

      override def getByPrefix(blockHashPrefix: String): Task[Option[BlockMsgWithTransform]] =
        underlyingBlockStorage.getByPrefix(blockHashPrefix)

      override def isEmpty: Task[Boolean] = underlyingBlockStorage.isEmpty

      override def put(
          blockHash: BlockHash,
          blockMsgWithTransform: BlockMsgWithTransform
      ): Task[Unit] =
        for {
          _ <- blockMsgWithTransform.blockMessage.fold(().pure[Task])(
                b => deployStorage.writer.addAsExecuted(b) >> dagStorage.insert(b).void
              )
          _ <- underlyingBlockStorage.put(blockHash, blockMsgWithTransform)
        } yield ()

      override def getBlockInfo(blockHash: BlockHash): Task[Option[BlockInfo]] =
        underlyingBlockStorage.getBlockInfo(blockHash)

      override def getBlockSummary(blockHash: BlockHash): Task[Option[BlockSummary]] =
        underlyingBlockStorage.getBlockSummary(blockHash)

      override def getBlockInfoByPrefix(blockHashPrefix: String): Task[Option[BlockInfo]] =
        underlyingBlockStorage.getBlockInfoByPrefix(blockHashPrefix)

      override def findBlockHashesWithDeployHashes(
          deployHashes: List[DeployHash]
      ): Task[Map[DeployHash, Set[BlockHash]]] =
        underlyingBlockStorage.findBlockHashesWithDeployHashes(deployHashes)

      override def checkpoint(): Task[Unit] = ???

      override def clear(): Task[Unit] = ???

      override def close(): Task[Unit] = ???
    }
}
