package io.casperlabs.blockstorage.benchmarks

import io.casperlabs.blockstorage.DagStorage
import io.casperlabs.blockstorage.benchmarks.StorageBenchSuite._
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
    (0 until StorageBenchSuite.preAllocSize) foreach { _ =>
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
        StorageBenchSuite.blocksIter.next()._2.blockMessage.get
      )
      .runSyncUnsafe()
}

class FileDagStorageWithLmdbBlockStorageBench extends DagStorageBench {
  override val dagStorage = fileDagStorage(lmdbBlockStorage)
}

class FileDagStorageWithInMemBlockStorageBench extends DagStorageBench {
  override val dagStorage = fileDagStorage(inMemBlockStorage)
}

class FileDagStorageWithFileLmdbIndexBlockStorageBench extends DagStorageBench {
  override val dagStorage = fileDagStorage(fileLmdbIndexBlockStorage)
}

class IndexedDagStorageWithLmdbBlockStorageBench extends DagStorageBench {
  override val dagStorage: DagStorage[Task] =
    indexedDagStorage(
      fileDagStorage(lmdbBlockStorage)
    )
}

class IndexedDagStorageWithFileLmdbIndexBlockStorageBench extends DagStorageBench {
  override val dagStorage: DagStorage[Task] =
    indexedDagStorage(
      fileDagStorage(fileLmdbIndexBlockStorage)
    )
}
