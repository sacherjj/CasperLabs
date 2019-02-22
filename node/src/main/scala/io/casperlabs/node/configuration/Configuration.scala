package io.casperlabs.node.configuration
import java.nio.file.{Path, Paths}

import cats.data.Validated._
import cats.data.ValidatedNel
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.validated._
import io.casperlabs.blockstorage.{BlockDagFileStorage, LMDBBlockStore}
import io.casperlabs.casper.CasperConf
import io.casperlabs.comm.PeerNode
import io.casperlabs.shared.StoreType
import shapeless._

final case class Configuration(
    command: Configuration.Command,
    server: Configuration.Server,
    grpcServer: Configuration.GrpcServer,
    tls: Configuration.Tls,
    casper: CasperConf,
    blockStorage: LMDBBlockStore.Config,
    blockDagStorage: BlockDagFileStorage.Config,
    kamon: Configuration.Kamon
)

private case class DefaultConf(c: ConfigurationSoft) extends AnyVal

object Configuration {
  case class Kamon(
      prometheus: Boolean,
      influx: Option[Influx],
      zipkin: Boolean,
      sigar: Boolean
  )

  case class Influx(
      hostname: String,
      port: Int,
      database: String,
      protocol: String,
      authentication: Option[InfluxDbAuthentication]
  )

  case class InfluxDbAuthentication(
      user: String,
      password: String
  )

  case class Server(
      host: Option[String],
      port: Int,
      httpPort: Int,
      kademliaPort: Int,
      dynamicHostAddress: Boolean,
      noUpnp: Boolean,
      defaultTimeout: Int,
      bootstrap: PeerNode,
      standalone: Boolean,
      genesisValidator: Boolean,
      dataDir: Path,
      mapSize: Long,
      storeType: StoreType,
      maxNumOfConnections: Int,
      maxMessageSize: Int,
      chunkSize: Int
  )
  case class GrpcServer(
      host: String,
      socket: Path,
      portExternal: Int,
      portInternal: Int
  )
  case class Tls(
      certificate: Path,
      key: Path,
      customCertificateLocation: Boolean,
      customKeyLocation: Boolean,
      secureRandomNonBlocking: Boolean
  )

  sealed trait Command extends Product with Serializable
  object Command {
    final case object Diagnostics extends Command
    final case object Run         extends Command
  }

  def parse(
      args: Array[String],
      envVars: Map[String, String]
  ): ValidatedNel[String, Configuration] = {
    val either = for {
      defaults <- ConfigurationSoft.tryDefault
      confSoft <- ConfigurationSoft.parse(args, envVars)
      command  <- Options.parseCommand(args, defaults)
    } yield parseToActual(command, defaults, confSoft)

    either.fold(_.invalidNel[Configuration], identity)
  }

  private[configuration] def parseToActual(
      command: Command,
      defaultConf: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, Configuration] = {
    implicit val default: DefaultConf = DefaultConf(defaultConf)
    implicit val c: ConfigurationSoft = confSoft
    (
      parseServer,
      parseGrpcServer,
      parseTls,
      parseCasper,
      parseBlockStorage,
      parseBlockDagStorage,
      parseKamon
    ).mapN(Configuration(command, _, _, _, _, _, _, _))
  }

  private def parseKamon(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, Kamon] = {
    val influx = parseInflux.toOption

    (
      toValidated(_.metrics.prometheus, "Kamon.prometheus"),
      toValidated(_.metrics.zipkin, "Kamon.zipkin"),
      toValidated(_.metrics.sigar, "Kamon.sigar")
    ) mapN (Kamon(_, influx, _, _))
  }

  private def parseInflux(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, Influx] = {
    val influxAuth = parseInfluxAuth.toOption
    (
      toValidated(_.influx.hostname, "Influx.hostname"),
      toValidated(_.influx.port, "Influx.port"),
      toValidated(_.influx.database, "Influx.database"),
      toValidated(_.influx.protocol, "Influx.protocol")
    ) mapN (Influx(_, _, _, _, influxAuth))
  }

  private def parseInfluxAuth(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, InfluxDbAuthentication] =
    (
      toValidated(_.influxAuth.user, "[Influx.authentication.user]"),
      toValidated(_.influxAuth.password, "[Influx.authentication.password]")
    ) mapN InfluxDbAuthentication

  private def parseServer(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, Configuration.Server] =
    (
      conf.server.host.validNel[String],
      toValidated(_.server.port, "Server.port"),
      toValidated(_.server.httpPort, "Server.httpPort"),
      toValidated(_.server.kademliaPort, "Server.kademliaPort"),
      toValidated(_.server.dynamicHostAddress, "Server.dynamicHostAddress"),
      toValidated(_.server.noUpnp, "Server.noUpnp"),
      toValidated(_.server.defaultTimeout, "Server.defaultTimeout"),
      toValidated(_.server.bootstrap, "Server.bootstrap"),
      toValidated(_.server.standalone, "Server.standalone"),
      toValidated(_.casper.approveGenesis, "Casper.approveGenesis"),
      toValidated(_.server.dataDir, "Server.dataDir"),
      toValidated(_.server.mapSize, "Server.mapSize"),
      toValidated(_.server.storeType, "Server.storeType"),
      toValidated(_.server.maxNumOfConnections, "Server.maxNumOfConnections"),
      toValidated(_.server.maxMessageSize, "Server.maxMessageSize"),
      toValidated(_.server.chunkSize, "Server.chunkSize")
    ).mapN(Configuration.Server.apply).map { server =>
      // Do not exceed HTTP2 RFC 7540
      val maxMessageSize = Math.min(server.maxMessageSize, 16 * 1024 * 1024)
      val chunkSize      = Math.min(server.chunkSize, maxMessageSize)
      server.copy(
        maxMessageSize = maxMessageSize,
        chunkSize = chunkSize
      )
    }

  private def parseGrpcServer(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, Configuration.GrpcServer] =
    (
      toValidated(_.grpc.host, "GrpcServer.host"),
      toValidated(_.grpc.socket, "GrpcServer.socket"),
      toValidated(_.grpc.portExternal, "GrpcServer.portExternal"),
      toValidated(_.grpc.portInternal, "GrpcServer.portInternal")
    ).mapN(Configuration.GrpcServer.apply)

  private def parseTls(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, Configuration.Tls] =
    (
      default.c.server.dataDir.fold("Default Server.dataDir".invalidNel[Path])(_.validNel[String]),
      toValidated(_.server.dataDir, "Server.dataDir"),
      default.c.tls.certificate
        .fold("Default Tls.certificate".invalidNel[Path])(_.validNel[String]),
      default.c.tls.key.fold("Default Tls.key".invalidNel[Path])(_.validNel[String]),
      toValidated(_.tls.certificate, "Tls.certificate"),
      toValidated(_.tls.key, "Tls.key"),
      toValidated(_.tls.secureRandomNonBlocking, "Tls.secureRandomNonBlocking")
    ).mapN(
      (
          defaultDataDir,
          dataDir,
          defaultCertificate,
          defaultKey,
          certificate,
          key,
          secureRandomNonBlocking
      ) => {
        val isCertificateCustomLocation =
          certificate.toAbsolutePath.toString
            .stripPrefix(dataDir.toAbsolutePath.toString) !=
            defaultCertificate.toAbsolutePath.toString
              .stripPrefix(defaultDataDir.toAbsolutePath.toString)
        val isKeyCustomLocation = key.toAbsolutePath.toString
          .stripPrefix(dataDir.toAbsolutePath.toString) !=
          defaultKey.toAbsolutePath.toString
            .stripPrefix(defaultDataDir.toAbsolutePath.toString)

        Configuration.Tls(
          certificate,
          key,
          isCertificateCustomLocation,
          isKeyCustomLocation,
          secureRandomNonBlocking
        )
      }
    )

  private def parseCasper(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, CasperConf] =
    (
      conf.casper.validatorPublicKey.validNel[String],
      conf.casper.validatorPrivateKey
        .map(_.asLeft[Path])
        .orElse(conf.casper.validatorPrivateKeyPath.map(_.asRight[String]))
        .validNel[String],
      toValidated(_.casper.validatorSigAlgorithm, "Casper.sigAlgorithm"),
      toValidated(_.casper.bondsFile, "Casper.bondsFile"),
      conf.casper.knownValidatorsFile.validNel[String],
      toValidated(_.casper.numValidators, "Casper.numValidators"),
      toValidated(_.casper.genesisPath.withDataDir, "Casper.genesisPath"),
      toValidated(_.casper.walletsFile, "Casper.walletsFile"),
      toValidated(_.casper.minimumBond, "Casper.minimumBond"),
      toValidated(_.casper.maximumBond, "Casper.maximumBond"),
      toValidated(_.casper.hasFaucet, "Casper.hasFaucet"),
      toValidated(_.casper.requiredSigs, "Casper.requiredSigs"),
      toValidated(_.casper.shardId, "Casper.shardId"),
      toValidated(_.server.standalone, "Server.standalone"),
      toValidated(_.casper.approveGenesis, "Casper.approveGenesis"),
      toValidated(_.casper.approveGenesisInterval, "Casper.approveGenesisInterval"),
      toValidated(_.casper.approveGenesisDuration, "Casper.approveGenesisDuration"),
      conf.casper.deployTimestamp.validNel[String]
    ).mapN(CasperConf.apply)

  private def parseBlockStorage(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, LMDBBlockStore.Config] =
    (
      toValidated(_.lmdb.path.withDataDir, "Lmdb.path"),
      toValidated(_.lmdb.blockStoreSize, "Lmdb.blockStoreSize"),
      toValidated(_.lmdb.maxDbs, "Lmdb.maxDbs"),
      toValidated(_.lmdb.maxReaders, "Lmdb.maxReaders"),
      toValidated(_.lmdb.useTls, "Lmdb.useTls")
    ).mapN(LMDBBlockStore.Config.apply)

  private def parseBlockDagStorage(
      implicit
      default: DefaultConf,
      conf: ConfigurationSoft
  ): ValidatedNel[String, BlockDagFileStorage.Config] =
    (
      toValidated(
        _.blockstorage.latestMessagesLogPath.withDataDir,
        "BlockDagFileStorage.latestMessagesLogPath"
      ),
      toValidated(
        _.blockstorage.latestMessagesCrcPath.withDataDir,
        "BlockDagFileStorage.latestMessagesCrcPath"
      ),
      toValidated(
        _.blockstorage.blockMetadataLogPath.withDataDir,
        "BlockDagFileStorage.blockMetadataLogPath"
      ),
      toValidated(
        _.blockstorage.blockMetadataCrcPath.withDataDir,
        "BlockDagFileStorage.blockMetadataCrcPath"
      ),
      toValidated(
        _.blockstorage.checkpointsDirPath.withDataDir,
        "BlockDagFileStorage.checkpointsDirPath"
      ),
      toValidated(
        _.blockstorage.latestMessagesLogMaxSizeFactor,
        "BlockDagFileStorage.latestMessagesLogMaxSizeFactor"
      )
    ).mapN(BlockDagFileStorage.Config.apply)

  private[configuration] def toValidated[A](
      selectField: ConfigurationSoft => Option[A],
      fieldName: String
  )(
      implicit
      ev: A <:!< ConfigurationSoft.RelativePath,
      c: ConfigurationSoft,
      d: DefaultConf
  ): ValidatedNel[String, A] = {
    def adjustPath(path: Path): Option[Path] =
      for {
        defaultDataDir <- d.c.server.dataDir
        dataDir        <- c.server.dataDir
        newPath = path.toAbsolutePath.toString
          .replace(defaultDataDir.toAbsolutePath.toString, dataDir.toAbsolutePath.toString)
      } yield Paths.get(newPath)

    selectField(c)
      .flatMap {
        case p: Path => adjustPath(p).asInstanceOf[Option[A]]
        case v       => Some(v)
      }
      .fold(s"$fieldName is not defined".invalidNel[A])(_.validNel[String])
  }

  private[configuration] def combineWithDataDir(
      relativePath: ConfigurationSoft => String
  )(implicit c: ConfigurationSoft): ConfigurationSoft => Option[Path] =
    _ => c.server.dataDir.map(_.resolve(relativePath(c)))
}
