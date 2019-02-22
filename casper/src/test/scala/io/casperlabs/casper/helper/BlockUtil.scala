package io.casperlabs.casper.helper
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.ProtoUtil.hashSignedBlock
import io.casperlabs.casper.util.implicits._

import scala.util.Random

object BlockUtil {
  def resignBlock(b: BlockMessage, sk: Array[Byte]): BlockMessage = {
    val blockHash =
      hashSignedBlock(b.header.get, b.sender, b.sigAlgorithm, b.seqNum, b.shardId, b.extraBytes)
    val sig = ByteString.copyFrom(b.signFunction(blockHash.toByteArray, sk))
    b.withBlockHash(blockHash).withSig(sig)
  }

  def generateValidator(prefix: String = ""): ByteString =
    ByteString.copyFromUtf8(prefix + Random.nextString(20)).substring(0, 32)

  def generateHash(prefix: String = ""): BlockHash =
    ByteString.copyFromUtf8(prefix + Random.nextString(20)).substring(0, 32)
}
