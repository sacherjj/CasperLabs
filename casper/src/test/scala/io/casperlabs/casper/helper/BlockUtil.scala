package io.casperlabs.casper.helper
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.ProtoUtil.hashSignedBlock
import io.casperlabs.casper.util.implicits._

object BlockUtil {
  def resignBlock(b: BlockMessage, sk: Array[Byte]): BlockMessage = {
    val blockHash =
      hashSignedBlock(b.header.get, b.sender, b.sigAlgorithm, b.seqNum, b.shardId, b.extraBytes)
    val sig = ByteString.copyFrom(b.signFunction(blockHash.toByteArray, sk))
    b.withBlockHash(blockHash).withSig(sig)
  }
}
