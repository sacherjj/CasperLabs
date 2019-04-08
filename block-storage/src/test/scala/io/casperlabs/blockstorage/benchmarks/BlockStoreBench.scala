package io.casperlabs.blockstorage.benchmarks

import BlockStoreBenchSuite._
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
abstract class BlockStoreBench {
  val blockStore: BlockStore[Task]
  var inserted: Iterator[BlockHash] = _

  @Setup(Level.Iteration)
  def setupWithRandomData(): Unit = {
    val hashes = Array.fill[BlockHash](preAllocSize)(null)

    for (i <- 0 until preAllocSize) {
      val block = randomBlock
      blockStore.put(block).runSyncUnsafe()
      hashes(i) = block._1
    }

    inserted = repeatedIteratorFrom(hashes.toIndexedSeq)
    System.gc()
  }

  @TearDown(Level.Iteration)
  def clearStore(): Unit =
    blockStore.clear().runSyncUnsafe()

  @Benchmark
  def put(): Unit =
    blockStore.put(blocksIter.next()).runSyncUnsafe()

  @Benchmark
  def getRandom() =
    blockStore.get(randomHash).runSyncUnsafe()

  @Benchmark
  def getInserted() =
    blockStore.get(inserted.next()).runSyncUnsafe()

  @Benchmark
  def findRandom() =
    blockStore.find(_ == randomHash).runSyncUnsafe()

  @Benchmark
  def findInserted() =
    blockStore.find(_ == inserted.next()).runSyncUnsafe()

  @Benchmark
  def checkpoint() =
    blockStore.checkpoint().runSyncUnsafe()

  @Benchmark
  def containsRandom() =
    blockStore.contains(randomHash).runSyncUnsafe()

  @Benchmark
  def containsInserted() =
    blockStore.contains(inserted.next()).runSyncUnsafe()
}

class InMemBench extends BlockStoreBench {
  override val blockStore = Init.inMemBlockStore
}

class LMDBBench extends BlockStoreBench {
  override val blockStore = Init.lmdbBlockStore
}

class FileLMDBIndexBench extends BlockStoreBench {
  override val blockStore = Init.fileLmdbIndexBlockStore
}
