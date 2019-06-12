package io.casperlabs.blockstorage.benchmarks

import StoreBenchSuite._
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
    val hashes = Array.fill[BlockHash](StoreBenchSuite.preAllocSize)(null)

    for (i <- 0 until StoreBenchSuite.preAllocSize) {
      val (blockHash, blockMsgWithTransform) = randomBlock
      blockStore.put(blockHash, blockMsgWithTransform).runSyncUnsafe()
      hashes(i) = blockHash
    }

    inserted = repeatedIteratorFrom(hashes.toIndexedSeq)
    System.gc()
  }

  @TearDown(Level.Iteration)
  def clearStore(): Unit =
    blockStore.clear().runSyncUnsafe()

  @Benchmark
  def put(): Unit = {
    val (blockHash, blockMsgWithTransform) = StoreBenchSuite.blocksIter.next()
    blockStore
      .put(
        blockHash,
        blockMsgWithTransform
      )
      .runSyncUnsafe()
  }

  @Benchmark
  def getRandom() =
    blockStore.get(randomHash).runSyncUnsafe()

  @Benchmark
  def getInserted() =
    blockStore.get(inserted.next()).runSyncUnsafe()

  @Benchmark
  def findRandom() =
    blockStore.findBlockHash(_ == randomHash).runSyncUnsafe()

  @Benchmark
  def findInserted() =
    blockStore.findBlockHash(_ == inserted.next()).runSyncUnsafe()

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
