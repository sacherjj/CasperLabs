package io.casperlabs.casper.util

import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.casper.consensus.state

class ProtocolVersions private (l: List[BlockThreshold]) {
  def versionAt(blockHeight: Long): state.ProtocolVersion =
    l.collectFirst {
      case BlockThreshold(blockHeightMin, protocolVersion) if blockHeightMin <= blockHeight =>
        protocolVersion
    }.get // This cannot throw because we validate in `apply` that list is never empty.

  def fromBlock(
      b: Block
  ): state.ProtocolVersion =
    versionAt(b.getHeader.rank)
}

object ProtocolVersions {

  final case class BlockThreshold(blockHeightMin: Long, version: state.ProtocolVersion)

  private implicit val blockThresholdOrdering: Ordering[BlockThreshold] =
    Ordering.by[BlockThreshold, Long](_.blockHeightMin).reverse

  def apply(l: List[BlockThreshold]): ProtocolVersions = {
    val sortedList = l.sorted(blockThresholdOrdering)

    require(sortedList.size >= 1, "List cannot be empty.")
    require(
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
          currThreshold.version.value == protocolVersionsSeen.last.value - 1,
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
    List(BlockThreshold(0, state.ProtocolVersion(1)))
  )

}
