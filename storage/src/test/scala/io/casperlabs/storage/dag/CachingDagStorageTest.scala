package io.casperlabs.storage.dag

import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.instances.long._
import cats.instances.map._
import cats.instances.set._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.storage.dag.CachingDagStorageTest.{CachingDagStorageTestData, MockMetrics}
import io.casperlabs.storage.{ArbitraryStorageData, SQLiteFixture}
import monix.eval.Task
import org.scalatest._

import scala.concurrent.duration._

class CachingDagStorageTest
    extends WordSpec
    with SQLiteFixture[CachingDagStorageTestData]
    with WordSpecLike
    with Matchers
    with ArbitraryStorageData {

  override def db: String = "/tmp/caching_dag_storage_test.db"

  private implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    maxSessionCodeBytes = 1,
    maxPaymentCodeBytes = 1,
    minSessionCodeBytes = 1,
    minPaymentCodeBytes = 1
  )

  private val sampleBlock: Block = sample(
    arbBlock.arbitrary.suchThat(b => b.parentHashes.nonEmpty && b.justifications.nonEmpty)
  )
  private val parents: Seq[ByteString] = sampleBlock.parentHashes.toList
  private val justifications: Seq[ByteString] =
    sampleBlock.justifications.map(_.latestBlockHash).toList

  private def prepareTestEnvironment(cacheSize: Long, neighborhoodRange: Int) = {
    implicit val metrics: MockMetrics = new MockMetrics()
    for {
      dagStorage <- SQLiteDagStorage.create[Task](xa, xa)
      cache <- CachingDagStorage[Task](
                dagStorage,
                cacheSize,
                neighborhoodBefore = neighborhoodRange,
                neighborhoodAfter = neighborhoodRange
              )
    } yield CachingDagStorageTestData(
      underlying = dagStorage,
      cache = cache,
      metrics = metrics
    )
  }

  override def createTestResource: Task[CachingDagStorageTestData] =
    prepareTestEnvironment(cacheSize = 1024L * 1024L * 25L, neighborhoodRange = 1)

  private def verifyCached[A](
      name: String,
      expected: A
  )(
      get: CachingDagStorage[Task] => Task[A]
  ): Unit = {
    // Store it through cache
    runSQLiteTest {
      case CachingDagStorageTestData(_, cache, metrics) =>
        for {
          _        <- cache.insert(sampleBlock)
          thing    <- get(cache)
          _        = thing shouldBe expected
          counters <- metrics.counterRef.get
          _        = counters(name) shouldBe 1
        } yield ()
    }
    // Store it through underlying
    runSQLiteTest {
      case CachingDagStorageTestData(underlying, cache, metrics) =>
        for {
          _        <- underlying.insert(sampleBlock)
          thing    <- get(cache)
          _        = thing shouldBe expected
          counters <- metrics.counterRef.get
          _        = counters(name) shouldBe 2
        } yield ()
    }
  }

  "CachingDagStorage" when {
    "children aren't in the cache" should {
      "get them from the underlying store and not cache it" in runSQLiteTest({
        case CachingDagStorageTestData(
            underlying,
            cache,
            metrics
            ) =>
          for {
            _        <- underlying.insert(sampleBlock)
            children <- Task.traverse(parents)(cache.children).map(_.reduce(_ |+| _))
            _        = children shouldBe Set(sampleBlock.blockHash)
            _        <- Task.traverse(parents)(cache.children).map(_.reduce(_ |+| _))
            counters <- metrics.counterRef.get
            _        = counters("children") shouldBe (parents.size * 4)
          } yield ()
      })
    }

    "justifications aren't in the cache" should {
      "get them from the underlying store and not cache it" in runSQLiteTest({
        case CachingDagStorageTestData(
            underlying,
            cache,
            metrics
            ) =>
          for {
            _ <- underlying.insert(sampleBlock)
            blockHashes <- Task
                            .traverse(justifications)(cache.justificationToBlocks)
                            .map(_.reduce(_ |+| _))
            _        = blockHashes shouldBe Set(sampleBlock.blockHash)
            _        <- Task.traverse(justifications)(cache.justificationToBlocks).map(_.reduce(_ |+| _))
            counters <- metrics.counterRef.get
            _        = counters("justificationToBlocks") shouldBe (justifications.size * 4)
          } yield ()
      })
    }

    "a block added into the store" should {
      "cache children and justification to blocks" in runSQLiteTest({
        case CachingDagStorageTestData(
            underlying,
            cache,
            metrics
            ) =>
          for {
            _                 <- cache.insert(sampleBlock)
            childrenFromCache <- Task.traverse(parents)(cache.children).map(_.reduce(_ |+| _))
            blockHashesFromCache <- Task
                                     .traverse(justifications)(cache.justificationToBlocks)
                                     .map(_.reduce(_ |+| _))
            counters <- metrics.counterRef.get
            _        = counters("insert") shouldBe 2
            _        = counters("children") shouldBe parents.size
            _        = counters("justificationToBlocks") shouldBe justifications.size
            childrenFromStorage <- Task
                                    .traverse(parents)(underlying.children)
                                    .map(_.reduce(_ |+| _))
            blockHashesFromStorage <- Task
                                       .traverse(justifications)(underlying.justificationToBlocks)
                                       .map(_.reduce(_ |+| _))
            _ = childrenFromCache shouldBe childrenFromStorage
            _ = childrenFromCache shouldBe Set(sampleBlock.blockHash)
            _ = blockHashesFromCache shouldBe blockHashesFromStorage
            _ = blockHashesFromCache shouldBe Set(sampleBlock.blockHash)
          } yield ()
      })

      "evict items if max size threshold is reached" in {
        runSQLiteTest(
          resources = prepareTestEnvironment(cacheSize = 64L * 10, neighborhoodRange = 1),
          test = {
            case CachingDagStorageTestData(_, cache, metrics) =>
              // 1 parent and 1 justification will result
              // in 32 (key) + 32 (parent hash or block hash) = 64 bytes
              // needed for reproducibility
              def genBlock =
                sampleBlock
                  .update(_.header.parentHashes := List(sample(genHash)))
                  .update(
                    _.header.justifications := List(
                      Block.Justification(ByteString.EMPTY, sample(genHash))
                    )
                  )
                  .update(_.blockHash := sample(genHash))

              val blocksNum   = 20
              val otherBlocks = List.fill(blocksNum)(genBlock)
              val allParents  = otherBlocks.flatMap(_.parents.toList)
              val allJustifications =
                otherBlocks.flatMap(_.justifications.map(_.latestBlockHash).toList)
              for {
                _ <- Task.traverse(otherBlocks)(cache.insert)
                _ <- Task
                      .traverse(allParents)(cache.children)
                _ <- Task
                      .traverse(allJustifications)(cache.justificationToBlocks)
                counters <- metrics.counterRef.get
                // Cache eviction is non-deterministic, so can't check for precise size
                _ = assert(counters("children") > allParents.size)
                _ = assert(counters("justificationToBlocks") > allJustifications.size)
              } yield ()
          },
          timeout = 15.seconds
        )
      }
    }
  }

  "CachingDagStorage" should {
    "cache `children`" in verifyCached("children", Set(sampleBlock.blockHash)) { store =>
      store.children(parents.head)
    }
    "cache `justificationToBlocks`" in verifyCached(
      "justificationToBlocks",
      Set(sampleBlock.blockHash)
    ) { store =>
      store.justificationToBlocks(justifications.head)
    }
    "cache neighborhood on lookup" in runSQLiteTest(
      resources = prepareTestEnvironment(cacheSize = 1024L * 1024L * 25L, neighborhoodRange = 1),
      test = {
        case CachingDagStorageTestData(underlying, cache, mockMetrics) =>
          def genChild(parent: Block) =
            parent
              .update(_.header.rank := parent.rank + 1)
              .update(_.header.parentHashes := List(parent.blockHash))
              .update(_.blockHash := sample(genHash))

          val grandGrandParent =
            sampleBlock
              .update(_.header.parentHashes := Nil)
              .update(_.header.justifications := Nil)

          val grandParent =
            genChild(grandGrandParent)

          val parent        = genChild(grandParent)
          val justification = genChild(grandParent)

          val child =
            genChild(parent).update(
              _.header.justifications := List(
                Justification(sample(genHash), justification.blockHash)
              )
            )

          for {
            // Inserting directly bypassing cache
            _ <- List(grandGrandParent, grandParent, parent, justification, child).traverse(
                  underlying.insert
                )
            // Should cache neighborhood on lookup
            _ <- cache.lookup(parent.blockHash).foreachL { maybeMessage =>
                  maybeMessage should not be empty
                }
            queriesNumBefore <- mockMetrics.counterRef.get.map(_.values.sum)
            // Should not query underlying storage again
            _ <- cache.lookup(parent.blockHash).foreachL { maybeMessage =>
                  maybeMessage should not be empty
                }
            queriesNumAfter <- mockMetrics.counterRef.get.map(_.values.sum)
          } yield {
            Option(cache.messagesCache.getIfPresent(child.blockHash)) should not be empty
            Option(cache.messagesCache.getIfPresent(parent.blockHash)) should not be empty
            Option(cache.messagesCache.getIfPresent(justification.blockHash)) should not be empty
            Option(cache.messagesCache.getIfPresent(grandParent.blockHash)) should not be empty
            Option(cache.messagesCache.getIfPresent(grandGrandParent.blockHash)) shouldBe None
            // 'queriesNumAfter' should count only the 'lookup' on CachingDagStorage itself
            queriesNumBefore shouldBe (queriesNumAfter - 1)
          }
      },
      timeout = 15.seconds
    )
  }
}

object CachingDagStorageTest {
  // Using the metrics added by MeteredDagStorage and MeteredDagRepresentation
  class MockMetrics() extends Metrics.MetricsNOP[Task] {
    val counterRef: Ref[Task, Map[String, Long]] = Ref.unsafe[Task, Map[String, Long]](Map.empty)

    override def incrementCounter(name: String, delta: Long = 1)(
        implicit ev: Metrics.Source
    ): Task[Unit] =
      counterRef.update {
        _ |+| Map(name -> delta)
      }
  }

  case class CachingDagStorageTestData(
      underlying: DagStorage[Task] with DagRepresentation[Task],
      cache: CachingDagStorage[Task],
      metrics: MockMetrics
  )
}
