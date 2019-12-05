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

  implicit val protocolVersionOrdering: Ordering[state.ProtocolVersion] =
    Ordering.by[state.ProtocolVersion, (Int, Int, Int)](pv => (pv.major, pv.minor, pv.patch))

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

  /** Returns a [[state.ProtocolVersion]] at block height. */
  def versionAt(blockHeight: Long): F[state.ProtocolVersion]

  /** Returns a [[state.ProtocolVersion]] at specific block. */
  def protocolFromBlock(b: Block): F[state.ProtocolVersion]

  /** Returns a configuration at specific block height.
    *
    * Note that this "merges" all configurations up to that block height.
    * Specifically if certain conifguration parameter isn't defined at latest
    * upgrade point, latest one will be used.
    */
  def configAt(blockHeight: Long): F[Config]
}

object CasperLabsProtocol {
  import ProtocolVersions.Config

  def unsafe[F[_]: Applicative](
      versions: (Long, state.ProtocolVersion, Option[ipc.ChainSpec.DeployConfig])*
  ): CasperLabsProtocol[F] = {
    def toDeployConfig(ipcDeployConfig: Option[ipc.ChainSpec.DeployConfig]): DeployConfig =
      ipcDeployConfig
        .map(d => DeployConfig(d.maxTtlMillis, d.maxDependencies))
        .getOrElse(DeployConfig())

    def toConfig(
        blockHeightMin: Long,
        protocolVersion: state.ProtocolVersion,
        deployConfig: Option[ipc.ChainSpec.DeployConfig]
    ): Config =
      Config(blockHeightMin, protocolVersion, toDeployConfig(deployConfig))

    val underlying = ProtocolVersions(versions.map {
      case (rank, protocolVersion, ipcDeployConfig) =>
        Config(rank, protocolVersion, toDeployConfig(ipcDeployConfig))
    }.toList)

    type T[A1] = (Long, state.ProtocolVersion, Option[A1])
    def merge[A1](c1: T[A1], c2: T[A1]): T[A1] = (c2._1, c2._2, c2._3.orElse(c1._3))

    // Merges configs, starting from Genesis and applying changes from later configs.
    // Ex: Genesis => (Upgrade1 or Genesis) => (Upgrade2 or Upgrade1 or Genesis) => …
    val merged =
      ProtocolVersions(
        versions
          .sortBy(_._1)
          .scan(versions.head)(merge)
          .map((toConfig _).tupled)
          .toList
          .drop(1) // We have to drop the first element since `scan` will return Genesis twice (Genesis, Genesis or Genesis, …).
      )

    new CasperLabsProtocol[F] {
      def versionAt(blockHeight: Long): F[state.ProtocolVersion] =
        underlying.versionAt(blockHeight).pure[F]
      def protocolFromBlock(b: Block): F[state.ProtocolVersion] = underlying.fromBlock(b).pure[F]
      def configAt(blockHeight: Long): F[Config]                = merged.configAt(blockHeight).pure[F]
    }
  }

  def apply[F[_]: MonadThrowable](
      configs: (Long, state.ProtocolVersion, Option[ipc.ChainSpec.DeployConfig])*
  ): F[CasperLabsProtocol[F]] =
    MonadThrowable[F].catchNonFatal(unsafe(configs: _*))

  def fromChainSpec[F[_]: MonadThrowable](spec: ipc.ChainSpec): F[CasperLabsProtocol[F]] = {
    val versions = (
      0L,
      spec.getGenesis.getProtocolVersion,
      spec.getGenesis.deployConfig
    ) +:
      spec.upgrades.map(
        up =>
          (
            up.getActivationPoint.rank,
            up.getProtocolVersion,
            up.newDeployConfig
          )
      )
    apply(versions: _*)
  }
}
