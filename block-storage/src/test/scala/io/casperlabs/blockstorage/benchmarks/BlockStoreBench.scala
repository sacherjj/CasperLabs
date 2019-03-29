package io.casperlabs.blockstorage.benchmarks

import java.nio.file.Paths

import cats.Monad
import cats.instances.list._
import cats.syntax.traverse._
import BlockStoreBenchSuite._
import io.casperlabs.blockstorage._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
abstract class BlockStoreBench {
  val blockStore: BlockStore[Task]

  @Setup(Level.Iteration)
  def setupWithRandomData(): Unit =
    preFilling.toList.traverse(b => blockStore.put(b)).runSyncUnsafe()

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
  def getExistent() =
    blockStore.get(randomInserted._1).runSyncUnsafe()

  @Benchmark
  def findRandom() =
    blockStore.find(_ == randomHash).runSyncUnsafe()

  @Benchmark
  def findExistent() =
    blockStore.find(_ == randomInserted._1).runSyncUnsafe()

  @Benchmark
  def checkpoint() =
    blockStore.checkpoint().runSyncUnsafe()

  @Benchmark
  def containsRandom() =
    blockStore.contains(randomHash).runSyncUnsafe()

  @Benchmark
  def containsExistent() =
    blockStore.contains(randomInserted._1).runSyncUnsafe()
}

class InMemBench extends BlockStoreBench {
  override val blockStore: BlockStore[Task] = InMemBlockStore.create[Task](
    Monad[Task],
    InMemBlockStore.emptyMapRef[Task].runSyncUnsafe(),
    metricsNop
  )
}

class LMDBBench extends BlockStoreBench {
  override val blockStore: BlockStore[Task] = LMDBBlockStore.create(
    LMDBBlockStore.Config(
      dir = Paths.get("/tmp/lmdb_block_store"),
      blockStoreSize = 1073741824,
      maxDbs = 1,
      maxReaders = 126,
      useTls = false
    )
  )
}

class FileLMDBIndexBench extends BlockStoreBench {
  override val blockStore: BlockStore[Task] =
    FileLMDBIndexBlockStore
      .create[Task](
        FileLMDBIndexBlockStore.Config(
          storagePath = Paths.get("/tmp/file_lmdb_storage"),
          indexPath = Paths.get("/tmp/file_lmdb_index"),
          checkpointsDirPath = Paths.get("/tmp/file_lmdb_checkpoints"),
          mapSize = 1073741824
        )
      )
      .runSyncUnsafe()
      .right
      .get
}
