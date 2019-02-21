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

  /**
    * All [[java.nio.file.Path]] fields must be wrapped into [[io.casperlabs.node.configuration.Configuration.adjustPath]].
    *
    * Otherwise a Path field will not respect server.dataDir changes.
    */
  private[configuration] def parseToActual(
      command: Command,
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, Configuration] =
    (
      parseServer(confSoft),
      parseGrpcServer(default, confSoft),
      parseTls(default, confSoft),
      parseCasper(default, confSoft),
      parseBlockStorage(default, confSoft),
      parseBlockDagStorage(default, confSoft),
      parseKamon(confSoft)
    ).mapN(Configuration(command, _, _, _, _, _, _, _))

  private def parseKamon(
      conf: ConfigurationSoft
  ): ValidatedNel[String, Kamon] = {
    val influx = parseInflux(conf).toOption
    (
      optToValidated(conf.metrics.prometheus, "Kamon.prometheus"),
      optToValidated(conf.metrics.zipkin, "Kamon.zipkin"),
      optToValidated(conf.metrics.sigar, "Kamon.sigar")
    ) mapN (Kamon(_, influx, _, _))
  }

  private def parseInflux(soft: ConfigurationSoft): ValidatedNel[String, Influx] = {
    val influxAuth = parseInfluxAuth(soft).toOption
    (
      optToValidated(soft.influx.hostname, "Influx.hostname"),
      optToValidated(soft.influx.port, "Influx.port"),
      optToValidated(soft.influx.database, "Influx.database"),
      optToValidated(soft.influx.protocol, "Influx.protocol")
    ) mapN (Influx(_, _, _, _, influxAuth))
  }

  private def parseInfluxAuth(
      soft: ConfigurationSoft
  ): ValidatedNel[String, InfluxDbAuthentication] =
    (
      optToValidated(soft.influxAuth.user, "[Influx.authentication.user]"),
      optToValidated(soft.influxAuth.password, "[Influx.authentication.password]")
    ) mapN InfluxDbAuthentication

  private def parseServer(
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, Configuration.Server] =
    (
      confSoft.server.host.validNel[String],
      optToValidated(confSoft.server.port, "Server.port"),
      optToValidated(confSoft.server.httpPort, "Server.httpPort"),
      optToValidated(confSoft.server.kademliaPort, "Server.kademliaPort"),
      optToValidated(confSoft.server.dynamicHostAddress, "Server.dynamicHostAddress"),
      optToValidated(confSoft.server.noUpnp, "Server.noUpnp"),
      optToValidated(confSoft.server.defaultTimeout, "Server.defaultTimeout"),
      optToValidated(confSoft.server.bootstrap, "Server.bootstrap"),
      optToValidated(confSoft.server.standalone, "Server.standalone"),
      optToValidated(confSoft.casper.approveGenesis, "Casper.approveGenesis"),
      optToValidated(confSoft.server.dataDir, "Server.dataDir"),
      optToValidated(confSoft.server.mapSize, "Server.mapSize"),
      optToValidated(confSoft.server.storeType, "Server.storeType"),
      optToValidated(confSoft.server.maxNumOfConnections, "Server.maxNumOfConnections"),
      optToValidated(confSoft.server.maxMessageSize, "Server.maxMessageSize"),
      optToValidated(confSoft.server.chunkSize, "Server.chunkSize")
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
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, Configuration.GrpcServer] =
    (
      optToValidated(confSoft.grpc.host, "GrpcServer.host"),
      optToValidated(
        adjustPath(confSoft, confSoft.grpc.socket, default),
        "GrpcServer.socket"
      ),
      optToValidated(confSoft.grpc.portExternal, "GrpcServer.portExternal"),
      optToValidated(confSoft.grpc.portInternal, "GrpcServer.portInternal")
    ).mapN(Configuration.GrpcServer.apply)

  private def parseTls(
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, Configuration.Tls] =
    (
      optToValidated(default.server.dataDir, "Default Server.dataDir"),
      optToValidated(confSoft.server.dataDir, "Server.dataDir"),
      optToValidated(confSoft.tls.certificate, "Default Tls.certificate"),
      optToValidated(confSoft.tls.key, "Default Tls.key"),
      optToValidated(
        adjustPath(confSoft, confSoft.tls.certificate, default),
        "Tls.certificate"
      ),
      optToValidated(adjustPath(confSoft, confSoft.tls.key, default), "Tls.key"),
      optToValidated(confSoft.tls.secureRandomNonBlocking, "Tls.secureRandomNonBlocking")
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
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, CasperConf] =
    (
      confSoft.casper.validatorPublicKey.validNel[String],
      confSoft.casper.validatorPrivateKey
        .map(_.asLeft[Path])
        .orElse(confSoft.casper.validatorPrivateKeyPath.map(_.asRight[String]))
        .validNel[String],
      optToValidated(confSoft.casper.validatorSigAlgorithm, "Casper.sigAlgorithm"),
      optToValidated(
        adjustPath(confSoft, confSoft.casper.bondsFile, default),
        "Casper.bondsFile"
      ),
      confSoft.casper.knownValidatorsFile.validNel[String],
      optToValidated(confSoft.casper.numValidators, "Casper.numValidators"),
      optToValidated(
        combineWithDataDir(confSoft, confSoft.casper.genesisPath),
        "Casper.genesisPath"
      ),
      optToValidated(
        adjustPath(confSoft, confSoft.casper.walletsFile, default),
        "Casper.walletsFile"
      ),
      optToValidated(confSoft.casper.minimumBond, "Casper.minimumBond"),
      optToValidated(confSoft.casper.maximumBond, "Casper.maximumBond"),
      optToValidated(confSoft.casper.hasFaucet, "Casper.hasFaucet"),
      optToValidated(confSoft.casper.requiredSigs, "Casper.requiredSigs"),
      optToValidated(confSoft.casper.shardId, "Casper.shardId"),
      optToValidated(confSoft.server.standalone, "Server.standalone"),
      optToValidated(confSoft.casper.approveGenesis, "Casper.approveGenesis"),
      optToValidated(
        confSoft.casper.approveGenesisInterval,
        "Casper.approveGenesisInterval"
      ),
      optToValidated(
        confSoft.casper.approveGenesisDuration,
        "Casper.approveGenesisDuration"
      ),
      confSoft.casper.deployTimestamp.validNel[String]
    ).mapN(CasperConf.apply)

  private def parseBlockStorage(
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, LMDBBlockStore.Config] =
    (
      optToValidated(combineWithDataDir(confSoft, confSoft.lmdb.path), "Lmdb.path"),
      optToValidated(confSoft.lmdb.blockStoreSize, "Lmdb.blockStoreSize"),
      optToValidated(confSoft.lmdb.maxDbs, "Lmdb.maxDbs"),
      optToValidated(confSoft.lmdb.maxReaders, "Lmdb.maxReaders"),
      optToValidated(confSoft.lmdb.useTls, "Lmdb.useTls")
    ).mapN(LMDBBlockStore.Config.apply)

  private def parseBlockDagStorage(
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNel[String, BlockDagFileStorage.Config] =
    (
      optToValidated(
        combineWithDataDir(confSoft, confSoft.blockstorage.latestMessagesLogPath),
        "BlockDagFileStorage.latestMessagesLogPath"
      ),
      optToValidated(
        combineWithDataDir(confSoft, confSoft.blockstorage.latestMessagesCrcPath),
        "BlockDagFileStorage.latestMessagesCrcPath"
      ),
      optToValidated(
        combineWithDataDir(confSoft, confSoft.blockstorage.blockMetadataLogPath),
        "BlockDagFileStorage.blockMetadataLogPath"
      ),
      optToValidated(
        combineWithDataDir(confSoft, confSoft.blockstorage.blockMetadataCrcPath),
        "BlockDagFileStorage.blockMetadataCrcPath"
      ),
      optToValidated(
        combineWithDataDir(confSoft, confSoft.blockstorage.checkpointsDirPath),
        "BlockDagFileStorage.checkpointsDirPath"
      ),
      optToValidated(
        confSoft.blockstorage.latestMessagesLogMaxSizeFactor,
        "BlockDagFileStorage.latestMessagesLogMaxSizeFactor"
      )
    ).mapN(BlockDagFileStorage.Config.apply)

  private def optToValidated[A](opt: Option[A], fieldName: String): ValidatedNel[String, A] =
    opt.fold(s"$fieldName is not defined".invalidNel[A])(_.validNel[String])

  private[configuration] def adjustPath(
      conf: ConfigurationSoft,
      pathToCheck: Option[Path],
      default: ConfigurationSoft
  ): Option[Path] =
    adjustPathAsString(conf, pathToCheck.map(_.toAbsolutePath.toString), default).map(Paths.get(_))

  private[configuration] def combineWithDataDir(
      conf: ConfigurationSoft,
      relativePath: String
  ): Option[Path] =
    conf.server.dataDir.map(_.resolve(relativePath))

  private[configuration] def adjustPathAsString(
      conf: ConfigurationSoft,
      pathToCheck: Option[String],
      default: ConfigurationSoft
  ): Option[String] =
    for {
      defaultDataDir <- default.server.dataDir
      dataDir        <- conf.server.dataDir
      path           <- pathToCheck
    } yield path.replace(defaultDataDir.toAbsolutePath.toString, dataDir.toAbsolutePath.toString)
}
