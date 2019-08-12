package io.casperlabs.blockstorage.benchmarks

import StorageBenchSuite._
import io.casperlabs.blockstorage.BlockStorage.BlockHash
import io.casperlabs.blockstorage._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
abstract class BlockStorageBench {
  val blockStorage: BlockStorage[Task]
  var inserted: Iterator[BlockHash] = _

  @Setup(Level.Iteration)
  def setupWithRandomData(): Unit = {
    val hashes = Array.fill[BlockHash](StorageBenchSuite.preAllocSize)(null)

    for (i <- 0 until StorageBenchSuite.preAllocSize) {
      val (blockHash, blockMsgWithTransform) = randomBlock
      blockStorage.put(blockHash, blockMsgWithTransform).runSyncUnsafe()
      hashes(i) = blockHash
    }

    inserted = repeatedIteratorFrom(hashes.toIndexedSeq)
    System.gc()
  }

  @TearDown(Level.Iteration)
  def clearStore(): Unit =
    blockStorage.clear().runSyncUnsafe()

  @Benchmark
  def put(): Unit = {
    val (blockHash, blockMsgWithTransform) = StorageBenchSuite.blocksIter.next()
    blockStorage
      .put(
        blockHash,
        blockMsgWithTransform
      )
      .runSyncUnsafe()
  }

  @Benchmark
  def getRandom() =
    blockStorage.get(randomHash).runSyncUnsafe()

  @Benchmark
  def getInserted() =
    blockStorage.get(inserted.next()).runSyncUnsafe()

  @Benchmark
  def findRandom() =
    blockStorage.findBlockHash(_ == randomHash).runSyncUnsafe()

  @Benchmark
  def findInserted() =
    blockStorage.findBlockHash(_ == inserted.next()).runSyncUnsafe()

  @Benchmark
  def checkpoint() =
    blockStorage.checkpoint().runSyncUnsafe()

  @Benchmark
  def containsRandom() =
    blockStorage.contains(randomHash).runSyncUnsafe()

  @Benchmark
  def containsInserted() =
    blockStorage.contains(inserted.next()).runSyncUnsafe()
}

class InMemBench extends BlockStorageBench {
  override val blockStorage = Init.inMemBlockStorage
}

class LMDBBench extends BlockStorageBench {
  override val blockStorage = Init.lmdbBlockStorage
}

class FileLMDBIndexBench extends BlockStorageBench {
  override val blockStorage = Init.fileLmdbIndexBlockStorage
}
