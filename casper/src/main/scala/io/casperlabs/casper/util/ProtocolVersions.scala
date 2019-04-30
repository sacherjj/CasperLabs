package io.casperlabs.casper.util

import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.ipc

class ProtocolVersions private (l: List[BlockThreshold]) {
  def versionAt(blockHeight: Long): ipc.ProtocolVersion =
    l.collectFirst {
      case BlockThreshold(blockHeightMin, protocolVersion) if blockHeightMin <= blockHeight =>
        protocolVersion
    }.get // This cannot throw because we validate in `apply` that list is never empty.

  def fromBlockMessage(
      b: BlockMessage
  ): ipc.ProtocolVersion =
    versionAt(b.getBody.getState.blockNumber)
}

object ProtocolVersions {

  final case class BlockThreshold(blockHeightMin: Long, version: ipc.ProtocolVersion)

  private implicit val blockThresholdOrdering: Ordering[BlockThreshold] =
    Ordering.by[BlockThreshold, Long](_.blockHeightMin).reverse

  def apply(l: List[BlockThreshold]): ProtocolVersions = {
    val sortedList = l.sorted(blockThresholdOrdering)

    assert(sortedList.size >= 1, "List cannot be empty.")
    assert(
      sortedList.last.blockHeightMin == 0,
      "Lowest block threshold MUST have 0 as lower bound."
    )

    sortedList.tail.foldLeft(
      (Set(sortedList.head.blockHeightMin), List(sortedList.head.version))
    ) {
      case ((rangeMinAcc, protocolVersionsSeen), currThreshold) =>
        assert(
          !rangeMinAcc.contains(currThreshold.blockHeightMin),
          "Block thresholds' lower boundaries can't repeat."
        )
        assert(
          currThreshold.version.version == protocolVersionsSeen.last.version - 1,
          "Protocol versions should increase monotonically by 1."
        )
        (rangeMinAcc + currThreshold.blockHeightMin, protocolVersionsSeen :+ currThreshold.version)
    }

    new ProtocolVersions(sortedList)
  }

}

object CasperLabsProtocolVersions {

  // Specifies what protocol version to choose at the `blockThreshold` height.
  val thresholdsVersionMap: ProtocolVersions = ProtocolVersions(
    List(BlockThreshold(0, ipc.ProtocolVersion(1)))
  )

}
