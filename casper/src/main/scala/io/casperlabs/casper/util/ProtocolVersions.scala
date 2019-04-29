package io.casperlabs.casper.util

import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.ipc

import scala.collection.{SortedMap, SortedSet}

object ProtocolVersions {

  /** Specifies a block height range
    *
    * @param blockHeightMin lower bound
    * @param blockHeightMax upper bound. It's optional as there might be none.
    */
  final case class BlockThreshold(blockHeightMin: Long, blockHeightMax: Option[Long])

  def at(
      blockHeight: Long,
      map: ProtocolVersionsMap
  ): Option[ipc.ProtocolVersion] =
    map.versionAt(blockHeight)

  def fromBlockMessage(
      b: BlockMessage,
      map: ProtocolVersionsMap
  ): Option[ipc.ProtocolVersion] =
    at(b.getBody.getState.blockNumber, map)

}

class ProtocolVersionsMap private (map: SortedMap[BlockThreshold, ipc.ProtocolVersion]) {
  def versionAt(blockHeight: Long): Option[ipc.ProtocolVersion] =
    map
      .collectFirst {
        case (blockThreshold, protocolVersion) if blockThreshold.blockHeightMin <= blockHeight =>
          protocolVersion
      }
}
object ProtocolVersionsMap {

  private implicit val blockThresholdOrdering: Ordering[BlockThreshold] =
    Ordering.by[BlockThreshold, Long](_.blockHeightMin).reverse

  private implicit val protocolVersionOrdering: Ordering[ipc.ProtocolVersion] =
    Ordering.by[ipc.ProtocolVersion, Long](_.version)

  def apply(map: Map[BlockThreshold, ipc.ProtocolVersion]): ProtocolVersionsMap = {
    val sortedList
      : List[(BlockThreshold, ipc.ProtocolVersion)] = SortedMap(map.toSeq: _*).toList.reverse
    assert(
      sortedList.head._1.blockHeightMin == 0,
      "Lowest block threshold MUST have 0 as lower bound."
    )
    assert(
      sortedList.last._1.blockHeightMax.isEmpty,
      "Highest block threshold MUSTN'T have upper bound."
    )
    sortedList.tail.foldLeft((sortedList.head._1, SortedSet(sortedList.head._2))) {
      case ((rangeAccumulated, protocolVersionsSeen), (currThreshold, currVer)) =>
        assert(
          currVer.version == protocolVersionsSeen.last.version + 1,
          "Protocol versions should increase monotonically by 1."
        )
        assert(
          currThreshold.blockHeightMin > rangeAccumulated.blockHeightMax.get,
          "Block thresholds can't overlap."
        )
        assert(
          currThreshold.blockHeightMin == rangeAccumulated.blockHeightMax.get + 1,
          "Block thresholds have to be linear (no gaps)."
        )
        (
          BlockThreshold(rangeAccumulated.blockHeightMin, currThreshold.blockHeightMax),
          protocolVersionsSeen + currVer
        )
    }
    new ProtocolVersionsMap(SortedMap(map.toSeq: _*))
  }
}

object CasperLabsProtocolVersions {

  // Specifies what protocol version to choose at the `blockThreshold` height.
  val thresholdsVersionMap: ProtocolVersionsMap = ProtocolVersionsMap(
    Map(
      BlockThreshold(0, None) -> ipc.ProtocolVersion(1)
    )
  )

}
