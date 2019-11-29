package io.casperlabs.casper.util

import cats._
import cats.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.ProtocolVersions.Config
import io.casperlabs.casper.consensus.state
import io.casperlabs.ipc
import io.casperlabs.ipc.ChainSpec.DeployConfig
import simulacrum.typeclass

import scala.util.Try

class ProtocolVersions private (l: List[Config]) {
  private def configAtHeight(blockHeight: Long): Config =
    l.collectFirst {
      case c @ Config(blockHeightMin, _, _) if blockHeightMin <= blockHeight =>
        c
    }.get // This cannot throw because we validate in `apply` that list is never empty.

  def versionAt(blockHeight: Long): state.ProtocolVersion =
    configAtHeight(blockHeight).version

  def configAt(blockHeight: Long): Config =
    configAtHeight(blockHeight)

  def fromBlock(
      b: Block
  ): state.ProtocolVersion =
    versionAt(b.getHeader.rank)
}

object ProtocolVersions {
  final case class Config(
      blockHeightMin: Long,
      version: state.ProtocolVersion,
      deployConfig: DeployConfig
  )

  // Order thresholds from newest to oldest descending.
  private implicit val blockThresholdOrdering: Ordering[Config] =
    Ordering.by[Config, Long](_.blockHeightMin).reverse

  def apply(l: List[Config]): ProtocolVersions = {
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
  def configAt(blockHeight: Long): F[Config]
}

object CasperLabsProtocol {
  import ProtocolVersions.Config

  def unsafe[F[_]: Applicative](
      versions: (Long, state.ProtocolVersion, Int, Int)*
  ): CasperLabsProtocol[F] = {
    val configs = versions.map {
      case (rank, protocolVersion, maxTTL, maxDependencies) =>
        Config(rank, protocolVersion, DeployConfig(maxTTL, maxDependencies))
    }

    val underlying = ProtocolVersions(configs.toList)

    new CasperLabsProtocol[F] {
      def versionAt(blockHeight: Long)           = underlying.versionAt(blockHeight).pure[F]
      def protocolFromBlock(b: Block)            = underlying.fromBlock(b).pure[F]
      def configAt(blockHeight: Long): F[Config] = underlying.configAt(blockHeight).pure[F]

    }
  }

  def apply[F[_]: MonadThrowable](
      configs: (Long, state.ProtocolVersion, Int, Int)*
  ): F[CasperLabsProtocol[F]] =
    MonadThrowable[F].fromTry(Try(unsafe(configs: _*)))

  def fromChainSpec[F[_]: MonadThrowable](spec: ipc.ChainSpec): F[CasperLabsProtocol[F]] = {
    val versions = (
      0L,
      spec.getGenesis.getProtocolVersion,
      spec.getGenesis.getDeployConfig.maxTtlMillis,
      spec.getGenesis.getDeployConfig.maxDependencies
    ) +:
      spec.upgrades.map(
        up =>
          (
            up.getActivationPoint.rank,
            up.getProtocolVersion,
            up.getNewDeployConfig.maxTtlMillis,
            up.getNewDeployConfig.maxDependencies
          )
      )
    apply(versions: _*)
  }
}
