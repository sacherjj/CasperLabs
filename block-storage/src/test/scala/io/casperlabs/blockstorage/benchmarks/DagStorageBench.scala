package io.casperlabs.blockstorage.benchmarks

import io.casperlabs.blockstorage.DagStorage
import io.casperlabs.blockstorage.benchmarks.StoreBenchSuite._
import io.casperlabs.blockstorage.benchmarks.Init._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
abstract class DagStorageBench {
  val dagStorage: DagStorage[Task]

  @Setup(Level.Iteration)
  def setupWithRandomData(): Unit = {
    (0 until StoreBenchSuite.preAllocSize) foreach { _ =>
      dagStorage.insert(randomBlockMessage).runSyncUnsafe()
    }
    System.gc()
  }

  @TearDown(Level.Iteration)
  def clearStore(): Unit =
    dagStorage.clear().runSyncUnsafe()

  @Benchmark
  def getRepresentation() =
    dagStorage.getRepresentation.runSyncUnsafe()

  @Benchmark
  def checkpoint() =
    dagStorage.checkpoint().runSyncUnsafe()

  @Benchmark
  def insert() =
    dagStorage
      .insert(
        StoreBenchSuite.blocksIter.next()._2.blockMessage.get
      )
      .runSyncUnsafe()
}

class FileDagStorageWithLmdbBlockStoreBench extends DagStorageBench {
  override val dagStorage = fileDagStorage(lmdbBlockStore)
}

class FileDagStorageWithInMemBlockStoreBench extends DagStorageBench {
  override val dagStorage = fileDagStorage(inMemBlockStore)
}

class FileDagStorageWithFileLmdbIndexBlockStoreBench extends DagStorageBench {
  override val dagStorage = fileDagStorage(Init.fileLmdbIndexBlockStore)
}

class IndexedDagStorageWithFileLmdbStorageBench extends DagStorageBench {
  override val dagStorage: DagStorage[Task] =
    indexedDagStorage(
      fileDagStorage(Init.lmdbBlockStore)
    )
}

class IndexedDagStorageWithFileLmdbIndexBench extends DagStorageBench {
  override val dagStorage: DagStorage[Task] =
    indexedDagStorage(
      fileDagStorage(Init.fileLmdbIndexBlockStore)
    )
}
