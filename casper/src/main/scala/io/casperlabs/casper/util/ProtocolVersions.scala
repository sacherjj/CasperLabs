package io.casperlabs.casper.util

import cats.data.NonEmptyList
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.ipc

class ProtocolVersions private (l: NonEmptyList[BlockThreshold]) {
  def versionAt(blockHeight: Long): ipc.ProtocolVersion =
    l.collect {
      case BlockThreshold(blockHeightMin, protocolVersion) if blockHeightMin <= blockHeight =>
        protocolVersion
    }.head // This cannot throw because we validate in `apply` that list is never empty.
}

object ProtocolVersions {

  final case class BlockThreshold(blockHeightMin: Long, version: ipc.ProtocolVersion)

  def fromBlockMessage(
      b: BlockMessage,
      map: ProtocolVersions
  ): ipc.ProtocolVersion =
    map.versionAt(b.getBody.getState.blockNumber)

  private implicit val blockThresholdOrdering: Ordering[BlockThreshold] =
    Ordering.by[BlockThreshold, Long](_.blockHeightMin).reverse

  def at(blockHeight: Long, m: ProtocolVersions): ipc.ProtocolVersion =
    m.versionAt(blockHeight)

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

    new ProtocolVersions(NonEmptyList.fromListUnsafe(sortedList))
  }

}

object CasperLabsProtocolVersions {

  // Specifies what protocol version to choose at the `blockThreshold` height.
  val thresholdsVersionMap: ProtocolVersions = ProtocolVersions(
    List(BlockThreshold(0, ipc.ProtocolVersion(1)))
  )

}
