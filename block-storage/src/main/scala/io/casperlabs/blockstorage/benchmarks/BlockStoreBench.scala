package io.casperlabs.blockstorage.benchmarks

import java.nio.file.Paths

import cats.Monad
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
  val preFilledCount = 10000

  val blockStore: BlockStore[Task]

  @Setup(Level.Iteration)
  def setupWithRandomData: Unit =
    for (_ <- 0 to preFilledCount)
      blockStore.put(randomBlock).runSyncUnsafe()

  @TearDown(Level.Iteration)
  def clearStore: Unit =
    blockStore.clear().runSyncUnsafe()

  @Benchmark
  def put: Unit =
    blockStore.put(blocksIter.next()).runSyncUnsafe()

  @Benchmark
  def get: Unit =
    blockStore.get(randomBlockHash).runSyncUnsafe()

  @Benchmark
  def find: Unit =
    blockStore.find(_ == randomBlockHash).runSyncUnsafe()

  @Benchmark
  def checkpoint: Unit =
    blockStore.checkpoint().runSyncUnsafe()
}

class InMemState extends BlockStoreBench {
  override val blockStore: BlockStore[Task] = InMemBlockStore.create[Task](
    Monad[Task],
    InMemBlockStore.emptyMapRef[Task].runSyncUnsafe(),
    metricsNop
  )
}

class LMDBState extends BlockStoreBench {
  override val blockStore: BlockStore[Task] = LMDBBlockStore.create(
    LMDBBlockStore.Config(
      dir = Paths.get("/home/base"),
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

  def randomHexString(numchars: Int): String = {
    val sb = new StringBuffer
    while (sb.length < numchars) sb.append(Integer.toHexString(Random.nextInt))

    sb.toString.substring(0, numchars)
  }

  def randomBlock: (BlockHash, BlockMsgWithTransform) =
    (randomBlockHash, BlockStoreBenchSuite.randomBlockMessage)

  def randomBlockHash: BlockHash =
    BlockStoreBenchSuite.strToByteStr(BlockStoreBenchSuite.randomHexString(20))

  def randomBlockMessage: BlockMsgWithTransform = {
    val hash      = randomHexString(20)
    val validator = randomHexString(20)
    val version   = Random.nextLong()
    val timestamp = Random.nextLong()
    val parents   = (0 to Random.nextInt(3)) map (_ => randomHexString(20): ByteString)
    val justifications = (0 to Random.nextInt(3)) map (
        _ => Justification(randomHexString(20), randomHexString(20))
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
