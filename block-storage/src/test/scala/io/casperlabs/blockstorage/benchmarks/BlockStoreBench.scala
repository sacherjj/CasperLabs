package io.casperlabs.blockstorage.benchmarks

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
  override val blockStore = Init.inMemBlockStore
}

class LMDBBench extends BlockStoreBench {
  override val blockStore = Init.lmdbBlockStore
}

class FileLMDBIndexBench extends BlockStoreBench {
  override val blockStore = Init.fileLmdbIndexBlockStore
}
