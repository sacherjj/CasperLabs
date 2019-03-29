package io.casperlabs.blockstorage.benchmarks
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.casper.protocol.{BlockMessage, Header, Justification}
import io.casperlabs.metrics.Metrics
import io.casperlabs.{metrics, shared}
import io.casperlabs.shared.Log
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task

import scala.collection.immutable.IndexedSeq
import scala.util.Random

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
    (randomHash, randomBlockMsgWithTransform)

  def randomHash: BlockHash =
    strToByteStr(randomHexString(32))

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
          .withVersion(version)
          .withTimestamp(timestamp)
      )
      .withSender(validator)
  }

  def randomBlockMsgWithTransform: BlockMsgWithTransform =
    BlockMsgWithTransform(Some(randomBlockMessage), Seq.empty)

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
