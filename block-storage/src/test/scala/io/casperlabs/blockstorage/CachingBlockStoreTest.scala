package io.casperlabs.blockstorage

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import monix.eval.Task
import monix.execution.Scheduler
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.BlockMsgWithTransform
import org.scalatest._
import scala.concurrent.duration._

class CachingBlockStoreTest extends WordSpecLike with Matchers {
  import BlockStore.BlockHash
  import CachingBlockStoreTest.TestFixture

  val sampleBlock = blockImplicits.blockMsgWithTransformGen.sample.get

  def verifyCached[A](
      name: String,
      expected: A
  )(
      get: BlockStore[Task] => BlockHash => Task[A]
  ) = {
    // Store it through cache
    TestFixture() { x =>
      for {
        _        <- x.cache.put(sampleBlock)
        thing    <- get(x.cache)(sampleBlock.getBlockMessage.blockHash)
        _        = thing shouldBe expected
        counters <- x.metrics.counterRef.get
        _        = counters(name) shouldBe 1
      } yield ()
    }
    // Store it through underlying
    TestFixture() { x =>
      for {
        _        <- x.underlying.put(sampleBlock)
        thing    <- get(x.cache)(sampleBlock.getBlockMessage.blockHash)
        _        = thing shouldBe expected
        counters <- x.metrics.counterRef.get
        _        = counters(name) shouldBe 2
      } yield ()
    }
  }

  "CachingBlockStore" when {
    "a block is not in the cache" should {
      "get it from the underlying store and not cache it" in {
        TestFixture() { x =>
          for {
            _        <- x.underlying.put(sampleBlock)
            block    <- x.cache.get(sampleBlock.getBlockMessage.blockHash)
            _        = block shouldBe Some(sampleBlock)
            _        <- x.cache.get(sampleBlock.getBlockMessage.blockHash)
            counters <- x.metrics.counterRef.get
            _        = counters("get") shouldBe (2 + 2)
          } yield ()
        }
      }
    }

    "a block is added to the store" should {
      "cache it" in {
        TestFixture() { x =>
          for {
            _        <- x.cache.put(sampleBlock)
            blockC   <- x.cache.get(sampleBlock.getBlockMessage.blockHash)
            _        = blockC shouldBe Some(sampleBlock)
            counters <- x.metrics.counterRef.get
            _        = counters("put") shouldBe 2
            _        = counters("get") shouldBe 1
            blockU   <- x.cache.get(sampleBlock.getBlockMessage.blockHash)
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
        TestFixture(
          maxSizeBytes = sampleBlock.toByteArray.length.toLong * 10
        ) { x =>
          for {
            _        <- x.cache.put(sampleBlock)
            _        <- x.cache.get(sampleBlock.getBlockMessage.blockHash)
            _        <- otherBlocks.traverse(x.cache.put(_))
            _        <- x.cache.get(sampleBlock.getBlockMessage.blockHash)
            _        <- x.cache.get(otherBlocks.last.getBlockMessage.blockHash)
            counters <- x.metrics.counterRef.get
            _        = counters("get") shouldBe (1 + 2 + 1)
          } yield ()
        }
      }
    }
  }

  "CachingBlockStore" should {
    "cache `contains`" in {
      verifyCached("contains", true) { store =>
        store.contains(_)
      }
    }
    "cache `findBlockHash`" in {
      verifyCached("findBlockHash", Option(sampleBlock.getBlockMessage.blockHash)) {
        store => hash =>
          store.findBlockHash(_ == hash)
      }
    }
    "cache `getBlockSummary`" in {
      val summary = BlockSummary(
        sampleBlock.getBlockMessage.blockHash,
        sampleBlock.getBlockMessage.header,
        sampleBlock.getBlockMessage.signature
      )
      verifyCached("getBlockSummary", Option(summary)) { store =>
        store.getBlockSummary(_)
      }
    }
  }
}

object CachingBlockStoreTest {
  import BlockStore.BlockHash
  import Scheduler.Implicits.global

  // Using the metrics added by MeteredBlockStore
  class MockMetrics() extends Metrics.MetricsNOP[Task] {
    val counterRef = Ref.unsafe[Task, Map[String, Long]](Map.empty)

    override def incrementCounter(name: String, delta: Long = 1)(
        implicit ev: Metrics.Source
    ): Task[Unit] =
      counterRef.update {
        _ |+| Map(name -> delta)
      }
  }

  case class TestFixture(
      underlying: BlockStore[Task],
      cache: BlockStore[Task],
      metrics: MockMetrics
  )

  object TestFixture {
    def apply(
        maxSizeBytes: Long = Long.MaxValue
    )(f: TestFixture => Task[Unit]): Unit = {
      implicit val metrics = new MockMetrics()

      val test = {
        for {
          underlying <- InMemBlockStore.empty[Task]
          cache      <- CachingBlockStore[Task](underlying, maxSizeBytes)
          _          <- f(TestFixture(underlying, cache, metrics))
        } yield ()
      }

      test.runSyncUnsafe(5.seconds)
    }
  }
}
