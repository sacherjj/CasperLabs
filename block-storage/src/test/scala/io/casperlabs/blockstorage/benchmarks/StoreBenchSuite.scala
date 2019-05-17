package io.casperlabs.blockstorage.benchmarks

import java.nio.file.Files.createTempDirectory
import java.nio.file.Paths
import java.util.{Properties, UUID}

import cats.Monad
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.implicits.none
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.{
  BlockDagFileStorage,
  BlockDagStorage,
  BlockStore,
  FileLMDBIndexBlockStore,
  InMemBlockStore,
  IndexedBlockDagStorage,
  LMDBBlockStore
}
import io.casperlabs.casper.protocol.{
  ApprovedBlock,
  BlockMessage,
  Body,
  DeployCode,
  DeployData,
  Header,
  Justification,
  ProcessedDeploy
}
import io.casperlabs.ipc.Key.KeyInstance
import io.casperlabs.ipc.Transform.TransformInstance
import io.casperlabs.ipc.{DeployCode => _, _}
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.{metrics, shared}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.IndexedSeq
import scala.util.Random

object StoreBenchSuite {
  import scala.language.implicitConversions

  implicit def strToByteStr(str: String): ByteString =
    ByteString.copyFromUtf8(str)

  implicit val metricsNop: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
  implicit val logNop: Log[Task]         = new shared.Log.NOPLog[Task]

  private val props = {
    val p  = new Properties()
    val in = getClass.getResourceAsStream("/block-store-benchmark.properties")
    p.load(in)
    in.close()
    p
  }

  def getIntProp(name: String) =
    props.get(name).asInstanceOf[String].toInt

  val blockSize    = getIntProp("blockSizeInKb")
  val preCreated   = getIntProp("preCreatedBlocks")
  val preAllocSize = getIntProp("preAllocBlocksEachIteration")

  private val preCreatedBlocks = (0 to preCreated).map(_ => randomBlock)

  val blocksIter = repeatedIteratorFrom(preCreatedBlocks)

  def randomHexString(numchars: Int): String = {
    val sb = new StringBuffer
    while (sb.length < numchars) sb.append(Integer.toHexString(Random.nextInt))

    sb.toString.substring(0, numchars)
  }

  def randomBlock: (BlockHash, BlockMsgWithTransform) =
    (randomHash, randomBlockMsgWithTransform)

  def randomHash: BlockHash =
    strToByteStr(randomHexString(32))

  //The implementation assumes that this method will return a data
  //with approximately 1KB size
  //Take this into account before change it
  def randomDeployData: DeployData =
    DeployData()
      .withAddress(randomHexString(32))
      .withPayment(
        DeployCode()
          .withCode(randomHexString(512))
      )
      .withSession(
        DeployCode()
          .withCode(randomHexString(480))
      )

  def randomDeploy: ProcessedDeploy =
    ProcessedDeploy()
      .withDeploy(randomDeployData)

  def randomBody: Body =
    Body()
      .withDeploys(
        (0 to blockSize) map (_ => randomDeploy)
      )

  def randomBlockMessage: BlockMessage = {
    val hash      = randomHash
    val validator = randomHexString(32)
    val version   = Random.nextLong()
    val timestamp = Random.nextLong()
    val parents   = (0 to Random.nextInt(3)) map (_ => randomHash)
    val justifications = (0 to Random.nextInt(10)) map (
        _ => Justification(randomHash, randomHash)
    )
    BlockMessage(blockHash = hash)
      .withHeader(
        Header()
          .withParentsHashList(parents)
          .withProtocolVersion(version)
          .withTimestamp(timestamp)
      )
      .withSender(validator)
      .withBody(randomBody)
  }

  def randomKey: Key = Key(
    KeyInstance.Hash(KeyHash(randomHash))
  )

  def randomTransform: Transform = Transform(
    TransformInstance.AddI32(TransformAddInt32(Random.nextInt()))
  )

  def randomTransformEntry: TransformEntry =
    TransformEntry(Some(randomKey), Some(randomTransform))

  def randomBlockMsgWithTransform: BlockMsgWithTransform =
    BlockMsgWithTransform(
      Some(randomBlockMessage),
      (0 to Random.nextInt(25)).map(_ => randomTransformEntry)
    )

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

object Init {
  import StoreBenchSuite._

  def createPath(path: String) =
    Paths.get(s"/tmp/$path-${UUID.randomUUID()}")

  def lmdbBlockStore = LMDBBlockStore.create[Task](
    LMDBBlockStore.Config(
      dir = createPath("lmdb_block_store"),
      blockStoreSize = 1073741824L * 12,
      maxDbs = 1,
      maxReaders = 126,
      useTls = false
    )
  )

  def fileLmdbIndexBlockStore =
    FileLMDBIndexBlockStore
      .create[Task](
        FileLMDBIndexBlockStore.Config(
          storagePath = createPath("file_lmdb_storage"),
          indexPath = createPath("file_lmdb_index"),
          approvedBlockPath = createPath("file_lmdb_approvedBlock"),
          checkpointsDirPath = createPath("file_lmdb_checkpoints"),
          mapSize = 1073741824L * 12
        )
      )
      .runSyncUnsafe()
      .right
      .get

  def inMemBlockStore = InMemBlockStore.create[Task](
    Monad[Task],
    InMemBlockStore.emptyMapRef[Task].runSyncUnsafe(),
    Ref[Task].of(none[ApprovedBlock]).runSyncUnsafe(),
    metricsNop
  )

  def fileStorage(blockStore: BlockStore[Task]) =
    BlockDagFileStorage
      .create(
        BlockDagFileStorage.Config(
          dir = createTempDirectory("block_dag_file_storage")
        )
      )(
        Concurrent[Task],
        logNop,
        blockStore,
        metricsNop
      )
      .runSyncUnsafe()

  def indexedStorage(blockDagStorage: BlockDagStorage[Task]) =
    IndexedBlockDagStorage.create[Task](blockDagStorage).runSyncUnsafe()
}
