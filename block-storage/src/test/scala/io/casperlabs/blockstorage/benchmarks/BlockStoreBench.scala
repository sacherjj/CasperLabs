package io.casperlabs.blockstorage.benchmarks

import java.nio.file.Paths

import cats.Monad
import cats.syntax.traverse._
import cats.instances.list._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.{
  BlockStore,
  FileLMDBIndexBlockStore,
  InMemBlockStore,
  LMDBBlockStore
}
import io.casperlabs.casper.protocol.{BlockMessage, Header, Justification}
import io.casperlabs.{metrics, shared}
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.openjdk.jmh.annotations._

import scala.collection.immutable.IndexedSeq
import scala.util.Random
import io.casperlabs.blockstorage.benchmarks.BlockStoreBenchSuite._
import io.casperlabs.shared.Log

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
  def getRandom(): Unit =
    blockStore.get(randomHash).runSyncUnsafe()

  @Benchmark
  def getExistent(): Unit =
    blockStore.get(randomInserted._1).runSyncUnsafe()

  @Benchmark
  def findRandom(): Unit =
    blockStore.find(_ == randomHash).runSyncUnsafe()

  @Benchmark
  def findExistent(): Unit =
    blockStore.find(_ == randomInserted._1).runSyncUnsafe()

  @Benchmark
  def checkpoint(): Unit =
    blockStore.checkpoint().runSyncUnsafe()

  @Benchmark
  def containsRandom(): Unit =
    blockStore.contains(randomHash).runSyncUnsafe()

  @Benchmark
  def containsExistent(): Unit =
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

object BlockStoreBenchSuite {
  import scala.language.implicitConversions

  implicit def strToByteStr(str: String): ByteString =
    ByteString.copyFromUtf8(str)

  implicit val metricsNop: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
  implicit val logNop: Log[Task]         = new shared.Log.NOPLog[Task]

  private val preCreatedBlocks = (0 to 50000).map(_ => randomBlock)

  val blocksIter = repeatedIteratorFrom(preCreatedBlocks)
  val preFilling = (0 to 10000).map(_ => randomBlock)

  def randomInserted =
    preFilling(Random.nextInt(preFilling.size))

  def randomHexString(numchars: Int): String = {
    val sb = new StringBuffer
    while (sb.length < numchars) sb.append(Integer.toHexString(Random.nextInt))

    sb.toString.substring(0, numchars)
  }

  def randomBlock: (BlockHash, BlockMsgWithTransform) =
    (randomHash, randomBlockMessage)

  def randomHash: BlockHash =
    strToByteStr(randomHexString(32))

  def randomBlockMessage: BlockMsgWithTransform = {
    val hash      = randomHash
    val validator = randomHexString(32)
    val version   = Random.nextLong()
    val timestamp = Random.nextLong()
    val parents   = (0 to Random.nextInt(3)) map (_ => randomHash)
    val justifications = (0 to Random.nextInt(10)) map (
        _ => Justification(randomHash, randomHash)
    )

    val blockMsg = BlockMessage(blockHash = hash)
      .withHeader(
        Header()
          .withParentsHashList(parents)
          .withVersion(version)
          .withTimestamp(timestamp)
      )
      .withSender(validator)

    BlockMsgWithTransform(Some(blockMsg), Seq.empty)
  }

  //This is needed because the alternative `Iterator.continually(elems).flatten`
  //is not thread-safe, so it can throw OOB exception during the execution
  def repeatedIteratorFrom[A](elems: IndexedSeq[A]): Iterator[A] =
    Iterator
      .iterate((0, elems(0))) {
        case (n, e) =>
          val next = (n + 1) % elems.length
          (next, elems(next))
      }
      .map(_._2)
}
