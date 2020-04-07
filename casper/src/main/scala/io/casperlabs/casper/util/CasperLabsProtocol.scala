package io.casperlabs.casper.util

import cats.Applicative
import cats.implicits._
import io.casperlabs.casper.consensus.{state, Block}
import io.casperlabs.casper.util.ProtocolVersions.Config
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.ipc
import io.casperlabs.ipc.ChainSpec.DeployConfig
import simulacrum.typeclass
import io.casperlabs.models.Message.MainRank

@typeclass
trait CasperLabsProtocol[F[_]] {

  /** Returns a [[state.ProtocolVersion]] at block height. */
  def versionAt(blockHeight: MainRank): F[state.ProtocolVersion]

  /** Returns a [[state.ProtocolVersion]] at specific block. */
  def protocolFromBlock(b: Block): F[state.ProtocolVersion]

  /** Returns a configuration at specific block height.
    *
    * Note that this "merges" all configurations up to that block height.
    * Specifically if certain configuration parameter isn't defined at latest
    * upgrade point, latest one will be used.
    */
  def configAt(blockHeight: MainRank): F[Config]
}

object CasperLabsProtocol {
  import ProtocolVersions.Config

  def unsafe[F[_]: Applicative](
      versions: (Long, state.ProtocolVersion, Option[ipc.ChainSpec.DeployConfig])*
  ): CasperLabsProtocol[F] = {

    val underlying = ProtocolVersions(versions.toList.map {
      case (minBlockHeight, protocolVersion, maybeDeployConfig) =>
        // It's fine for an upgrade to not change the deploy configuration.
        Config(minBlockHeight, protocolVersion, maybeDeployConfig.getOrElse(DeployConfig()))
    })

    type T[A1] = (Long, state.ProtocolVersion, Option[A1])
    def merge[A1](c1: T[A1], c2: T[A1]): T[A1] = (c2._1, c2._2, c2._3.orElse(c1._3))

    // Merges configs, starting from Genesis and applying changes from later configs.
    // Ex: Genesis => (Upgrade1 or Genesis) => (Upgrade2 or Upgrade1 or Genesis) => …
    val merged =
      ProtocolVersions(
        versions
          .sortBy(_._1)
          .scan(versions.head)(merge)
          .drop(1) // We have to drop the first element since `scan` will return Genesis twice (Genesis, Genesis or Genesis, …).
          .map {
            case (minBlockHeight, protocolVersion, maybeDeployConfig) =>
              // We've collapsed all the upgrade configurations into Genesis.
              // We should have the deploy config from the Genesis at least.
              Config(
                minBlockHeight,
                protocolVersion,
                maybeDeployConfig.getOrElse(
                  throw new IllegalArgumentException(
                    "Missing DeployConfiguration. There was none in the Genesis config."
                  )
                )
              )
          }
          .toList
      )

    new CasperLabsProtocol[F] {
      def versionAt(blockHeight: MainRank): F[state.ProtocolVersion] =
        underlying.versionAt(blockHeight).pure[F]
      def protocolFromBlock(b: Block): F[state.ProtocolVersion] = underlying.fromBlock(b).pure[F]
      def configAt(blockHeight: MainRank): F[Config]            = merged.configAt(blockHeight).pure[F]
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
