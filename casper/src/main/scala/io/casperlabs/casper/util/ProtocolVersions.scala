package io.casperlabs.casper.util

import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.ipc

class ProtocolVersions private (l: List[BlockThreshold]) {
  def versionAt(blockHeight: Long): Option[ipc.ProtocolVersion] = l.collectFirst {
    case BlockThreshold(blockHeightMin, protocolVersion) if blockHeightMin <= blockHeight =>
      protocolVersion
  }
}

object ProtocolVersions {

  final case class BlockThreshold(blockHeightMin: Long, version: ipc.ProtocolVersion)

  def fromBlockMessage(
      b: BlockMessage,
      map: ProtocolVersions
  ): Option[ipc.ProtocolVersion] =
    map.versionAt(b.getBody.getState.blockNumber)

  private implicit val blockThresholdOrdering: Ordering[BlockThreshold] =
    Ordering.by[BlockThreshold, Long](_.blockHeightMin).reverse

  def at(blockHeight: Long, m: ProtocolVersions): Option[ipc.ProtocolVersion] =
    m.versionAt(blockHeight)

  def apply(l: List[BlockThreshold]): ProtocolVersions = {
    val sortedList = l.sorted(blockThresholdOrdering)

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
