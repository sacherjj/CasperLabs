package io.casperlabs.casper.util

import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.ipc

object ProtocolVersions {
  final case class BlockThreshold(blockHeight: Long)

  // Specifies what protocol version to choose at the `blockThreshold` height.
  private val thresholdsVersionMap: Map[BlockThreshold, ipc.ProtocolVersion] = Map(
    BlockThreshold(0) -> ipc.ProtocolVersion(1)
  )

  def apply(i: BlockThreshold): Option[ipc.ProtocolVersion] =
    thresholdsVersionMap
      .find {
        case (blockThreshold, _) => blockThreshold.blockHeight <= i.blockHeight
      }
      .map(_._2)

  def fromBlockMessage(b: BlockMessage): Option[ipc.ProtocolVersion] =
    apply(BlockThreshold(b.getBody.getState.blockNumber))

}
