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

  // Order thresholds from newest to oldest descending.
  private implicit val blockThresholdOrdering: Ordering[BlockThreshold] =
    Ordering.by[BlockThreshold, Long](_.blockHeightMin).reverse

  def apply(l: List[BlockThreshold]): ProtocolVersions = {
    val descendingList = l.sorted(blockThresholdOrdering)

    require(descendingList.size >= 1, "List cannot be empty.")
    require(
      descendingList.last.blockHeightMin == 0,
      "Lowest block threshold MUST have 0 as lower bound."
    )

    descendingList.tail.foldLeft(
      (Set(descendingList.head.blockHeightMin), descendingList.head.version)
    ) {
      case ((rangeMinAcc, nextVersion), prevThreshold) =>
        assert(
          !rangeMinAcc.contains(prevThreshold.blockHeightMin),
          "Block thresholds' lower boundaries can't repeat."
        )
        checkFollows(prevThreshold.version, nextVersion).foreach { msg =>
          assert(false, msg)
        }
        (rangeMinAcc + prevThreshold.blockHeightMin, prevThreshold.version)
    }

    new ProtocolVersions(descendingList)
  }

  def checkFollows(prev: state.ProtocolVersion, next: state.ProtocolVersion): Option[String] =
    if (next.major < 0 || next.minor < 0 || next.patch < 0 || prev.major < 0 || prev.minor < 0 || prev.patch < 0) {
      Some("Protocol versions cannot be negative.")
    } else if (next.major > prev.major + 1) {
      Some("Protocol major versions should increase monotonically by 1.")
    } else if (next.major == prev.major + 1) {
      if (next.minor != 0) {
        Some("Protocol minor versions should be 0 after major version change.")
      } else if (next.patch != 0) {
        Some("Protocol patch versions should be 0 after major version change.")
      } else {
        None
      }
    } else if (next.major == prev.major) {
      if (next.minor > prev.minor + 1) {
        Some(
          "Protocol minor versions should increase monotonically by 1 within the same major version."
        )
      } else if (next.minor == prev.minor + 1) {
        None
      } else if (next.minor == prev.minor) {
        if (next.patch <= prev.patch) {
          Some("Protocol patch versions should increase monotonically.")
        } else {
          None
        }
      } else {
        Some("Protocol minor versions should not go backwards within the same major version.")
      }
    } else {
      Some("Protocol major versions should not go backwards.")
    }
}

@typeclass
trait CasperLabsProtocol[F[_]] {
  def versionAt(blockHeight: Long): F[state.ProtocolVersion]
  def protocolFromBlock(b: Block): F[state.ProtocolVersion]
}

object CasperLabsProtocol {
  import ProtocolVersions.BlockThreshold

  // Specifies what protocol version to choose at the `blockThreshold` height.
  val thresholdsVersionMap: ProtocolVersions = ProtocolVersions(
    List(BlockThreshold(0, state.ProtocolVersion(1, 0, 0)))
  )

  def unsafe[F[_]: Applicative](
      versions: (Long, state.ProtocolVersion)*
  ): CasperLabsProtocol[F] = {
    val thresholds = versions.map {
      case (rank, version) =>
        BlockThreshold(rank, version)
    }

    val underlying = ProtocolVersions(thresholds.toList)

    new CasperLabsProtocol[F] {
      def versionAt(blockHeight: Long) = underlying.versionAt(blockHeight).pure[F]
      def protocolFromBlock(b: Block)  = underlying.fromBlock(b).pure[F]
    }
  }

  def apply[F[_]: MonadThrowable](
      versions: (Long, state.ProtocolVersion)*
  ): F[CasperLabsProtocol[F]] =
    MonadThrowable[F].fromTry(Try(unsafe(versions: _*)))

  def fromChainSpec[F[_]: MonadThrowable](spec: ipc.ChainSpec): F[CasperLabsProtocol[F]] = {
    val versions = (0L, spec.getGenesis.getProtocolVersion) +:
      spec.upgrades.map(up => (up.getActivationPoint.rank, up.getProtocolVersion))
    apply(versions: _*)
  }
}
