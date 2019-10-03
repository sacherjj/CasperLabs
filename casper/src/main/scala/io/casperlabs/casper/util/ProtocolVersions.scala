package io.casperlabs.casper.util

import cats._
import cats.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.ProtocolVersions.BlockThreshold
import io.casperlabs.casper.consensus.state
import io.casperlabs.ipc
import simulacrum.typeclass
import scala.util.Try

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

@typeclass
trait CasperLabsProtocolVersions[F[_]] {
  def versionAt(blockHeight: Long): F[state.ProtocolVersion]
  def fromBlock(b: Block): F[state.ProtocolVersion]
}

object CasperLabsProtocolVersions {
  import ProtocolVersions.BlockThreshold

  def unsafe[F[_]: Applicative](
      versions: (Long, state.ProtocolVersion)*
  ): CasperLabsProtocolVersions[F] = {
    val thresholds = versions.map {
      case (rank, version) =>
        BlockThreshold(rank, version)
    }

    val underlying = ProtocolVersions(thresholds.toList)

    new CasperLabsProtocolVersions[F] {
      def versionAt(blockHeight: Long) = underlying.versionAt(blockHeight).pure[F]
      def fromBlock(b: Block)          = underlying.fromBlock(b).pure[F]
    }
  }

  def apply[F[_]: MonadThrowable](
      versions: (Long, state.ProtocolVersion)*
  ): F[CasperLabsProtocolVersions[F]] =
    MonadThrowable[F].fromTry(Try(unsafe(versions: _*)))

  def fromChainSpec[F[_]: MonadThrowable](spec: ipc.ChainSpec): F[CasperLabsProtocolVersions[F]] = {
    val versions = (0L, spec.getGenesis.getProtocolVersion) +:
      spec.upgrades.map(up => (up.getActivationPoint.rank, up.getProtocolVersion))
    apply(versions: _*)
  }
}
