package io.casperlabs.node.configuration
import java.nio.file.{Path, Paths}

import cats.data.Validated._
import cats.data._
import cats.implicits._
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
    blockDagStorage: BlockDagFileStorage.Config
)

object Configuration {
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
      maxMessageSize: Int
  )
  case class GrpcServer(
      host: String,
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

  def printHelp: Either[String, () => Unit] =
    ConfigurationSoft.tryDefault.map { defaultConf => () =>
      Options.printHelp(defaultConf)
    }

  def parse(args: Array[String]): ValidatedNec[String, Configuration] = {
    val either = for {
      default  <- ConfigurationSoft.tryDefault
      confSoft <- ConfigurationSoft.parse(args)
      command  <- Options.parseCommand(args)
    } yield parseToActual(command, default, confSoft)

    either.fold(_.invalidNec[Configuration], identity)
  }

  private def parseToActual(
      command: Command,
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNec[String, Configuration] =
    (
      parseServer(confSoft),
      parseGrpcServer(confSoft),
      parseTls(default, confSoft),
      parseCasper(default, confSoft),
      parseBlockStorage(default, confSoft),
      parseBlockDagStorage(default, confSoft)
    ).mapN {
      case (server, grpc, tls, casper, lmdb, blockStorage) =>
        Configuration(command, server, grpc, tls, casper, lmdb, blockStorage)
    }

  private def parseServer(
      confSoft: ConfigurationSoft
  ): ValidatedNec[String, Configuration.Server] =
    (
      confSoft.server.flatMap(_.host).validNec[String],
      optToValidated(confSoft.server.flatMap(_.port), "Server.port"),
      optToValidated(confSoft.server.flatMap(_.httpPort), "Server.httpPort"),
      optToValidated(confSoft.server.flatMap(_.kademliaPort), "Server.kademliaPort"),
      optToValidated(confSoft.server.flatMap(_.dynamicHostAddress), "Server.dynamicHostAddress"),
      optToValidated(confSoft.server.flatMap(_.noUpnp), "Server.noUpnp"),
      optToValidated(confSoft.server.flatMap(_.defaultTimeout), "Server.defaultTimeout"),
      optToValidated(confSoft.server.flatMap(_.bootstrap), "Server.bootstrap"),
      optToValidated(confSoft.server.flatMap(_.standalone), "Server.standalone"),
      optToValidated(confSoft.casper.flatMap(_.approveGenesis), "Casper.approveGenesis"),
      optToValidated(confSoft.server.flatMap(_.dataDir), "Server.dataDir"),
      optToValidated(confSoft.server.flatMap(_.mapSize), "Server.mapSize"),
      optToValidated(confSoft.server.flatMap(_.storeType), "Server.storeType"),
      optToValidated(confSoft.server.flatMap(_.maxNumOfConnections), "Server.maxNumOfConnections"),
      optToValidated(confSoft.server.flatMap(_.maxMessageSize), "Server.maxMessageSize")
    ).mapN(Configuration.Server.apply)

  private def parseGrpcServer(
      confSoft: ConfigurationSoft
  ): ValidatedNec[String, Configuration.GrpcServer] =
    (
      optToValidated(confSoft.grpcServer.flatMap(_.host), "GrpcServer.host"),
      optToValidated(confSoft.grpcServer.flatMap(_.portExternal), "GrpcServer.portExternal"),
      optToValidated(confSoft.grpcServer.flatMap(_.portInternal), "GrpcServer.portInternal")
    ).mapN(Configuration.GrpcServer.apply)

  private def parseTls(
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNec[String, Configuration.Tls] =
    (
      optToValidated(confSoft.tls.flatMap(_.certificate), "Default Tls.certificate"),
      optToValidated(confSoft.tls.flatMap(_.key), "Default Tls.key"),
      optToValidated(
        adjustPath(confSoft, confSoft.tls.flatMap(_.certificate), default),
        "Tls.certificate"
      ),
      optToValidated(adjustPath(confSoft, confSoft.tls.flatMap(_.key), default), "Tls.key"),
      optToValidated(confSoft.tls.flatMap(_.secureRandomNonBlocking), "Tls.secureRandomNonBlocking")
    ).mapN((defaultCertificate, defaultKey, certificate, key, secureRandomNonBlocking) => {
      val isCertificateCustomLocation = certificate != defaultCertificate
      val isKeyCustomLocation         = key != defaultKey

      Configuration.Tls(
        certificate,
        key,
        isCertificateCustomLocation,
        isKeyCustomLocation,
        secureRandomNonBlocking
      )
    })

  private def parseCasper(
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNec[String, CasperConf] =
    (
      confSoft.casper.flatMap(_.publicKey).validNec[String],
      confSoft.casper
        .flatMap(_.privateKey)
        .map(_.asLeft[Path])
        .orElse(confSoft.casper.flatMap(_.privateKeyPath).map(_.asRight[String]))
        .validNec[String],
      optToValidated(confSoft.casper.flatMap(_.sigAlgorithm), "Casper.sigAlgorithm"),
      adjustPathAsString(confSoft, confSoft.casper.flatMap(_.bondsFile), default).validNec[String],
      confSoft.casper.flatMap(_.knownValidatorsFile).validNec[String],
      optToValidated(confSoft.casper.flatMap(_.numValidators), "Casper.numValidators"),
      optToValidated(confSoft.casper.flatMap(_.genesisPath), "Casper.genesisPath"),
      adjustPathAsString(confSoft, confSoft.casper.flatMap(_.walletsFile), default)
        .validNec[String],
      optToValidated(confSoft.casper.flatMap(_.minimumBond), "Casper.minimumBond"),
      optToValidated(confSoft.casper.flatMap(_.maximumBond), "Casper.maximumBond"),
      optToValidated(confSoft.casper.flatMap(_.hasFaucet), "Casper.hasFaucet"),
      optToValidated(confSoft.casper.flatMap(_.requiredSigs), "Casper.requiredSigs"),
      optToValidated(confSoft.casper.flatMap(_.shardId), "Casper.shardId"),
      optToValidated(confSoft.server.flatMap(_.standalone), "Server.standalone"),
      optToValidated(confSoft.casper.flatMap(_.approveGenesis), "Casper.approveGenesis"),
      optToValidated(
        confSoft.casper.flatMap(_.approveGenesisInterval),
        "Casper.approveGenesisInterval"
      ),
      optToValidated(
        confSoft.casper.flatMap(_.approveGenesisDuration),
        "Casper.approveGenesisDuration"
      ),
      confSoft.casper.flatMap(_.deployTimestamp).validNec[String]
    ).mapN(CasperConf.apply)

  private def parseBlockStorage(
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNec[String, LMDBBlockStore.Config] =
    (
      optToValidated(adjustPath(confSoft, confSoft.lmdb.flatMap(_.path), default), "Lmdb.path"),
      optToValidated(confSoft.lmdb.flatMap(_.blockStoreSize), "Lmdb.blockStoreSize"),
      optToValidated(confSoft.lmdb.flatMap(_.maxDbs), "Lmdb.maxDbs"),
      optToValidated(confSoft.lmdb.flatMap(_.maxReaders), "Lmdb.maxReaders"),
      optToValidated(confSoft.lmdb.flatMap(_.useTls), "Lmdb.useTls")
    ).mapN(LMDBBlockStore.Config.apply)

  private def parseBlockDagStorage(
      default: ConfigurationSoft,
      confSoft: ConfigurationSoft
  ): ValidatedNec[String, BlockDagFileStorage.Config] =
    (
      optToValidated(
        adjustPath(confSoft, confSoft.blockStorage.flatMap(_.latestMessagesLogPath), default),
        "BlockDagFileStorage.latestMessagesLogPath"
      ),
      optToValidated(
        adjustPath(confSoft, confSoft.blockStorage.flatMap(_.latestMessagesCrcPath), default),
        "BlockDagFileStorage.latestMessagesCrcPath"
      ),
      optToValidated(
        adjustPath(confSoft, confSoft.blockStorage.flatMap(_.blockMetadataLogPath), default),
        "BlockDagFileStorage.blockMetadataLogPath"
      ),
      optToValidated(
        adjustPath(confSoft, confSoft.blockStorage.flatMap(_.blockMetadataCrcPath), default),
        "BlockDagFileStorage.blockMetadataCrcPath"
      ),
      optToValidated(
        adjustPath(confSoft, confSoft.blockStorage.flatMap(_.checkpointsDirPath), default),
        "BlockDagFileStorage.checkpointsDirPath"
      ),
      optToValidated(
        confSoft.blockStorage.flatMap(_.latestMessagesLogMaxSizeFactor),
        "BlockDagFileStorage.latestMessagesLogMaxSizeFactor"
      )
    ).mapN(BlockDagFileStorage.Config.apply)

  private def optToValidated[A](opt: Option[A], fieldName: String): ValidatedNec[String, A] =
    opt.fold(s"$fieldName is not defined".invalidNec[A])(_.validNec[String])

  private[configuration] def adjustPath(
      conf: ConfigurationSoft,
      pathToCheck: Option[Path],
      default: ConfigurationSoft
  ): Option[Path] =
    adjustPathAsString(conf, pathToCheck.map(_.toAbsolutePath.toString), default).map(Paths.get(_))

  private[configuration] def adjustPathAsString(
      conf: ConfigurationSoft,
      pathToCheck: Option[String],
      default: ConfigurationSoft
  ): Option[String] =
    for {
      defaultDataDir <- default.server.flatMap(_.dataDir)
      _              = println(defaultDataDir.toAbsolutePath.toString)
      dataDir        <- conf.server.flatMap(_.dataDir)
      _              = println(dataDir.toAbsolutePath.toString)
      path           <- pathToCheck
      _              = println(path)
    } yield path.replace(defaultDataDir.toAbsolutePath.toString, dataDir.toAbsolutePath.toString)
}
