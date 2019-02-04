package io.casperlabs.node.configuration

import java.nio.file.Path

import cats.syntax.either._
import io.casperlabs.comm.PeerNode
import io.casperlabs.node.configuration.ConfigurationSoft.LmdbBlockStore
import io.casperlabs.shared.StoreType
import io.casperlabs.shared.Merge
import shapeless.LowPriority

import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.Try

case class ConfigurationSoft(
    server: Option[ConfigurationSoft.Server],
    grpc: Option[ConfigurationSoft.GrpcServer],
    tls: Option[ConfigurationSoft.Tls],
    casper: Option[ConfigurationSoft.Casper],
    lmdb: Option[ConfigurationSoft.LmdbBlockStore],
    blockstorage: Option[ConfigurationSoft.BlockDagFileStorage],
    metrics: Option[ConfigurationSoft.Metrics],
    influx: Option[ConfigurationSoft.Influx],
    influxAuth: Option[ConfigurationSoft.InfluxAuth]
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

  private[configuration] def parse(args: Array[String]): Either[String, ConfigurationSoft] =
    for {
      defaults               <- tryDefault
      cliConf                <- Options.parseConf(args, defaults)
      maybeRawTomlConfigFile = Options.tryReadConfigFile(args, defaults)
      maybeTomlConf          = maybeRawTomlConfigFile.map(_.flatMap(TomlReader.parse))
      result <- maybeTomlConf
                 .map(_.map(tomlConf => cliConf.fallbackTo(tomlConf).fallbackTo(defaults)))
                 .getOrElse(Right(cliConf.fallbackTo(defaults)))
    } yield result
}
