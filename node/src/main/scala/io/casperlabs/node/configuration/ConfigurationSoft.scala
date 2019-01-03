package io.casperlabs.node.configuration
import java.nio.file.Path

import cats.syntax.either._
import io.casperlabs.comm.PeerNode
import io.casperlabs.shared.StoreType
import shapeless.{Generic, Poly1}

import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.Try

private[configuration] case class ConfigurationSoft(
    server: Option[ConfigurationSoft.Server],
    grpc: Option[ConfigurationSoft.GrpcServer],
    tls: Option[ConfigurationSoft.Tls],
    casper: Option[ConfigurationSoft.Casper],
    lmdb: Option[ConfigurationSoft.LmdbBlockStore],
    blockStorage: Option[ConfigurationSoft.BlockDagFileStorage]
) {
  def fallbackTo(other: ConfigurationSoft): ConfigurationSoft = {
    val genConf = Generic[ConfigurationSoft]

    object fallback extends Poly1 {
      implicit def caseOption[A]
        : fallback.Case[(Option[A], Option[A])] { type Result = Option[A] } =
        at[(Option[A], Option[A])] { case (a, b) => a.orElse(b) }
      implicit def caseString = at[String] { identity }
    }

    object mapper extends Poly1 {
      implicit def caseServer =
        at[(Option[ConfigurationSoft.Server], Option[ConfigurationSoft.Server])] {
          case (a, b) =>
            (a, b) match {
              case (None, None)    => None
              case (Some(_), None) => a
              case (None, Some(_)) => b
              case (Some(sa), Some(sb)) =>
                val ha = Generic[ConfigurationSoft.Server].to(sa)
                val hb = Generic[ConfigurationSoft.Server].to(sb)
                Some(Generic[ConfigurationSoft.Server].from(ha.zip(hb).map(fallback)))
            }
        }

      implicit def caseGrpcServer =
        at[(Option[ConfigurationSoft.GrpcServer], Option[ConfigurationSoft.GrpcServer])] {
          case (a, b) =>
            (a, b) match {
              case (None, None)    => None
              case (Some(_), None) => a
              case (None, Some(_)) => b
              case (Some(sa), Some(sb)) =>
                val ha = Generic[ConfigurationSoft.GrpcServer].to(sa)
                val hb = Generic[ConfigurationSoft.GrpcServer].to(sb)
                Some(Generic[ConfigurationSoft.GrpcServer].from(ha.zip(hb).map(fallback)))
            }
        }

      implicit def caseTls =
        at[(Option[ConfigurationSoft.Tls], Option[ConfigurationSoft.Tls])] {
          case (a, b) =>
            (a, b) match {
              case (None, None)    => None
              case (Some(_), None) => a
              case (None, Some(_)) => b
              case (Some(sa), Some(sb)) =>
                val ha = Generic[ConfigurationSoft.Tls].to(sa)
                val hb = Generic[ConfigurationSoft.Tls].to(sb)
                Some(Generic[ConfigurationSoft.Tls].from(ha.zip(hb).map(fallback)))
            }
        }

      implicit def caseCasper =
        at[(Option[ConfigurationSoft.Casper], Option[ConfigurationSoft.Casper])] {
          case (a, b) =>
            (a, b) match {
              case (None, None)    => None
              case (Some(_), None) => a
              case (None, Some(_)) => b
              case (Some(sa), Some(sb)) =>
                val ha = Generic[ConfigurationSoft.Casper].to(sa)
                val hb = Generic[ConfigurationSoft.Casper].to(sb)
                Some(Generic[ConfigurationSoft.Casper].from(ha.zip(hb).map(fallback)))
            }
        }

      implicit def caseLmdb =
        at[(Option[ConfigurationSoft.LmdbBlockStore], Option[ConfigurationSoft.LmdbBlockStore])] {
          case (a, b) =>
            (a, b) match {
              case (None, None)    => None
              case (Some(_), None) => a
              case (None, Some(_)) => b
              case (Some(sa), Some(sb)) =>
                val ha = Generic[ConfigurationSoft.LmdbBlockStore].to(sa)
                val hb = Generic[ConfigurationSoft.LmdbBlockStore].to(sb)
                Some(Generic[ConfigurationSoft.LmdbBlockStore].from(ha.zip(hb).map(fallback)))
            }
        }

      implicit def caseBlockDagFileStorage =
        at[
          (
              Option[ConfigurationSoft.BlockDagFileStorage],
              Option[ConfigurationSoft.BlockDagFileStorage]
          )
        ] {
          case (a, b) =>
            (a, b) match {
              case (None, None)    => None
              case (Some(_), None) => a
              case (None, Some(_)) => b
              case (Some(sa), Some(sb)) =>
                val ha = Generic[ConfigurationSoft.BlockDagFileStorage].to(sa)
                val hb = Generic[ConfigurationSoft.BlockDagFileStorage].to(sb)
                Some(Generic[ConfigurationSoft.BlockDagFileStorage].from(ha.zip(hb).map(fallback)))
            }
        }
    }

    genConf.from(genConf.to(this).zip(genConf.to(other)).map(mapper))
  }
}

private[configuration] object ConfigurationSoft {
  private[configuration] case class Server(
      host: Option[String],
      port: Option[Int],
      httpPort: Option[Int],
      kademliaPort: Option[Int],
      dynamicHostAddress: Option[Boolean],
      noUpnp: Option[Boolean],
      defaultTimeout: Option[Int],
      bootstrap: Option[PeerNode],
      standalone: Option[Boolean],
      mapSize: Option[Long],
      storeType: Option[StoreType],
      dataDir: Option[Path],
      maxNumOfConnections: Option[Int],
      maxMessageSize: Option[Int]
  )

  private[configuration] case class LmdbBlockStore(
      path: Option[Path],
      blockStoreSize: Option[Long],
      maxDbs: Option[Int],
      maxReaders: Option[Int],
      useTls: Option[Boolean]
  )

  private[configuration] case class BlockDagFileStorage(
      latestMessagesLogPath: Option[Path],
      latestMessagesCrcPath: Option[Path],
      blockMetadataLogPath: Option[Path],
      blockMetadataCrcPath: Option[Path],
      checkpointsDirPath: Option[Path],
      latestMessagesLogMaxSizeFactor: Option[Int]
  )

  private[configuration] case class GrpcServer(
      host: Option[String],
      socket: Option[Path],
      portExternal: Option[Int],
      portInternal: Option[Int]
  )

  private[configuration] case class Tls(
      certificate: Option[Path],
      key: Option[Path],
      secureRandomNonBlocking: Option[Boolean]
  )

  private[configuration] case class Casper(
      publicKey: Option[String],
      privateKey: Option[String],
      privateKeyPath: Option[Path],
      sigAlgorithm: Option[String],
      bondsFile: Option[String],
      knownValidatorsFile: Option[String],
      numValidators: Option[Int],
      genesisPath: Option[Path],
      walletsFile: Option[String],
      minimumBond: Option[Long],
      maximumBond: Option[Long],
      hasFaucet: Option[Boolean],
      requiredSigs: Option[Int],
      shardId: Option[String],
      approveGenesis: Option[Boolean],
      approveGenesisInterval: Option[FiniteDuration],
      approveGenesisDuration: Option[FiniteDuration],
      deployTimestamp: Option[Long]
  )

  private[configuration] def tryDefault: Either[String, ConfigurationSoft] =
    Try(Source.fromResource("default-configuration.toml").mkString).toEither
      .leftMap(_.getMessage)
      .flatMap(raw => TomlReader.parse(raw))

  private[configuration] def parse(args: Array[String]): Either[String, ConfigurationSoft] =
    for {
      default                <- tryDefault
      cliConf                <- Options.parseConf(args)
      maybeRawTomlConfigFile = Options.tryReadConfigFile(args)
      maybeTomlConf          = maybeRawTomlConfigFile.map(_.flatMap(TomlReader.parse))
      result <- maybeTomlConf
                 .map(_.map(tomlConf => cliConf.fallbackTo(tomlConf).fallbackTo(default)))
                 .getOrElse(Right(cliConf.fallbackTo(default)))
    } yield result
}
