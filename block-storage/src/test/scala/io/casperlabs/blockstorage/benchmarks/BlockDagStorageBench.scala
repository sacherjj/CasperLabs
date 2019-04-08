package io.casperlabs.blockstorage.benchmarks

import io.casperlabs.blockstorage.BlockDagStorage
import io.casperlabs.blockstorage.benchmarks.BlockStoreBenchSuite._
import io.casperlabs.blockstorage.benchmarks.Init._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
abstract class BlockDagStorageBench {
  val dagStore: BlockDagStorage[Task]

  @Setup(Level.Iteration)
  def setupWithRandomData(): Unit = {
    val preAllocSize = 100

    (0 until preAllocSize) foreach { _ =>
      dagStore.insert(randomBlockMessage).runSyncUnsafe()
    }
    System.gc()
  }

  @TearDown(Level.Iteration)
  def clearStore(): Unit =
    dagStore.clear().runSyncUnsafe()

  @Benchmark
  def getRepresentation() =
    dagStore.getRepresentation.runSyncUnsafe()

  @Benchmark
  def checkpoint() =
    dagStore.checkpoint().runSyncUnsafe()

  @Benchmark
  def insert() =
    dagStore.insert(blocksIter.next()._2.blockMessage.get).runSyncUnsafe()
}

class FileStorageWithLmdbBlockStoreBench extends BlockDagStorageBench {
  override val dagStore = fileStorage(lmdbBlockStore)
}

class FileStorageWithInMemBlockStoreBench extends BlockDagStorageBench {
  override val dagStore = fileStorage(inMemBlockStore)
}

class FileStorageWithFileLmdbIndexBlockStoreBench extends BlockDagStorageBench {
  override val dagStore = fileStorage(Init.fileLmdbIndexBlockStore)
}

class IndexedStorageWithFileLmdbStorageBench extends BlockDagStorageBench {
  override val dagStore: BlockDagStorage[Task] =
    indexedStorage(
      fileStorage(Init.lmdbBlockStore)
    )
}

class IndexedStorageWithFileLmdbIndexBench extends BlockDagStorageBench {
  override val dagStore: BlockDagStorage[Task] =
    indexedStorage(
      fileStorage(Init.fileLmdbIndexBlockStore)
    )
}
