package io.casperlabs.node.configuration

import java.nio.file.Path

import cats.data.ValidatedNel
import cats.syntax.apply._
import cats.syntax.either._
import io.casperlabs.comm.PeerNode
import io.casperlabs.shared.{Merge, StoreType}
import shapeless.LowPriority

import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.Try

case class ConfigurationSoft(
    server: ConfigurationSoft.Server,
    grpc: ConfigurationSoft.GrpcServer,
    tls: ConfigurationSoft.Tls,
    casper: ConfigurationSoft.Casper,
    lmdb: ConfigurationSoft.LmdbBlockStore,
    blockstorage: ConfigurationSoft.BlockDagFileStorage,
    metrics: ConfigurationSoft.Metrics,
    influx: ConfigurationSoft.Influx,
    influxAuth: ConfigurationSoft.InfluxAuth
) {
  implicit def fallback[A](implicit ev: LowPriority): Merge[Option[A]] =
    (l: Option[A], r: Option[A]) => l.orElse(r)

  def fallbackTo(other: ConfigurationSoft): ConfigurationSoft =
    Merge[ConfigurationSoft].merge(this, other)
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
      maxMessageSize: Option[Int],
      chunkSize: Option[Int]
  )

  private[configuration] case class LmdbBlockStore(
      blockStoreSize: Option[Long],
      maxDbs: Option[Int],
      maxReaders: Option[Int],
      useTls: Option[Boolean]
  ) {
    val path: String = "casper-block-store"
  }

  private[configuration] case class BlockDagFileStorage(
      latestMessagesLogMaxSizeFactor: Option[Int]
  ) {
    val latestMessagesLogPath: String = "casper-block-dag-file-storage-latest-messages-log"
    val latestMessagesCrcPath: String = "casper-block-dag-file-storage-latest-messages-crc"
    val blockMetadataLogPath: String  = "casper-block-dag-file-storage-block-metadata-log"
    val blockMetadataCrcPath: String  = "casper-block-dag-file-storage-block-metadata-crc"
    val checkpointsDirPath: String    = "casper-block-dag-file-storage-checkpoints"
  }

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
      validatorPublicKey: Option[String],
      validatorPrivateKey: Option[String],
      validatorPrivateKeyPath: Option[Path],
      validatorSigAlgorithm: Option[String],
      bondsFile: Option[Path],
      knownValidatorsFile: Option[String],
      numValidators: Option[Int],
      walletsFile: Option[Path],
      minimumBond: Option[Long],
      maximumBond: Option[Long],
      hasFaucet: Option[Boolean],
      requiredSigs: Option[Int],
      shardId: Option[String],
      approveGenesis: Option[Boolean],
      approveGenesisInterval: Option[FiniteDuration],
      approveGenesisDuration: Option[FiniteDuration],
      deployTimestamp: Option[Long]
  ) {
    val genesisPath: String = "genesis"
  }

  private[configuration] case class Metrics(
      prometheus: Option[Boolean],
      zipkin: Option[Boolean],
      sigar: Option[Boolean]
  )

  private[configuration] case class Influx(
      hostname: Option[String],
      port: Option[Int],
      database: Option[String],
      protocol: Option[String]
  )

  private[configuration] case class InfluxAuth(
      user: Option[String],
      password: Option[String]
  )

  private[configuration] def tryDefault: Either[String, ConfigurationSoft] =
    Try(Source.fromResource("default-configuration.toml").mkString).toEither
      .leftMap(_.getMessage)
      .flatMap(raw => TomlReader.parse(raw))

  private[configuration] def fromEnv(
      envVars: Map[String, String]
  ): ValidatedNel[String, ConfigurationSoft] =
    (
      EnvVarsParser[ConfigurationSoft.Server].parse(envVars, List("CL_SERVER")),
      EnvVarsParser[ConfigurationSoft.GrpcServer].parse(envVars, List("CL_GRPC")),
      EnvVarsParser[ConfigurationSoft.Tls].parse(envVars, List("CL_TLS")),
      EnvVarsParser[ConfigurationSoft.Casper].parse(envVars, List("CL_CASPER")),
      EnvVarsParser[ConfigurationSoft.LmdbBlockStore].parse(envVars, List("CL_LMDB")),
      EnvVarsParser[ConfigurationSoft.BlockDagFileStorage].parse(envVars, List("CL_BLOCKSTORAGE")),
      EnvVarsParser[ConfigurationSoft.Metrics].parse(envVars, List("CL_METRICS")),
      EnvVarsParser[ConfigurationSoft.Influx].parse(envVars, List("CL_INFLUX")),
      EnvVarsParser[ConfigurationSoft.InfluxAuth].parse(envVars, List("CL_INFLUX_AUTH"))
    ).mapN(ConfigurationSoft.apply)

  private[configuration] def parse(
      args: Array[String],
      envVars: Map[String, String]
  ): Either[String, ConfigurationSoft] =
    for {
      defaults               <- tryDefault
      cliConf                <- Options.parseConf(args, defaults)
      maybeRawTomlConfigFile = Options.tryReadConfigFile(args, defaults)
      maybeTomlConf          = maybeRawTomlConfigFile.map(_.flatMap(TomlReader.parse))
      envConf                <- fromEnv(envVars).toEither.leftMap(_.toList.mkString("\n"))
      cliWithEnv             = cliConf.fallbackTo(envConf)
      result <- maybeTomlConf
                 .map(_.map(tomlConf => cliWithEnv.fallbackTo(tomlConf).fallbackTo(defaults)))
                 .getOrElse(Right(cliWithEnv.fallbackTo(defaults)))
    } yield result
}
