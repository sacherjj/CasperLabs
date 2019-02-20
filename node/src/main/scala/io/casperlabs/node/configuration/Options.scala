package io.casperlabs.node.configuration

import java.nio.file.Path

import io.casperlabs.comm.PeerNode
import io.casperlabs.node.BuildInfo
import io.casperlabs.shared.StoreType
import org.rogach.scallop._
import cats.syntax.either._
import cats.syntax.option._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.io.Source
import scala.util.Try

private[configuration] object Converter {
  import Options._

  implicit val bootstrapAddressConverter: ValueConverter[PeerNode] = new ValueConverter[PeerNode] {
    def parse(s: List[(String, List[String])]): Either[String, Option[PeerNode]] =
      s match {
        case (_, uri :: Nil) :: Nil =>
          PeerNode
            .fromAddress(uri)
            .map(u => Right(Some(u)))
            .getOrElse(Left("can't parse the casperlabs node bootstrap address"))
        case Nil => Right(None)
        case _   => Left("provide the casperlabs node bootstrap address")
      }

    val argType: ArgType.V = ArgType.SINGLE
  }

  implicit val optionsFlagConverter: ValueConverter[Flag] = new ValueConverter[Flag] {
    def parse(s: List[(String, List[String])]): Either[String, Option[Flag]] =
      flagConverter.parse(s).map(_.map(flag))

    val argType: ArgType.V = ArgType.FLAG
  }

  implicit val finiteDurationConverter: ValueConverter[FiniteDuration] =
    new ValueConverter[FiniteDuration] {

      override def parse(s: List[(String, List[String])]): Either[String, Option[FiniteDuration]] =
        s match {
          case (_, duration :: Nil) :: Nil =>
            val finiteDuration = Some(Duration(duration)).collect { case f: FiniteDuration => f }
            finiteDuration.fold[Either[String, Option[FiniteDuration]]](
              Left("Expected finite duration.")
            )(fd => Right(Some(fd)))
          case Nil => Right(None)
          case _   => Left("Provide a duration.")
        }

      override val argType: ArgType.V = ArgType.SINGLE
    }

  implicit val storeTypeConverter: ValueConverter[StoreType] = new ValueConverter[StoreType] {
    def parse(s: List[(String, List[String])]): Either[String, Option[StoreType]] =
      s match {
        case (_, storeType :: Nil) :: Nil =>
          StoreType
            .from(storeType)
            .map(u => Right(Some(u)))
            .getOrElse(Left("can't parse the store type"))
        case Nil => Right(None)
        case _   => Left("provide the store type")
      }
    val argType: ArgType.V = ArgType.SINGLE
  }
}

private[configuration] object Options {
  import shapeless.tag.@@

  sealed trait FlagTag
  type Flag = Boolean @@ FlagTag

  def flag(b: Boolean): Flag = b.asInstanceOf[Flag]

  implicit def scallopOptionToOption[A](so: ScallopOption[A]): Option[A] = so.toOption

  // We need this conversion because ScallopOption[A] is invariant in A
  implicit def scallopOptionFlagToBoolean(so: ScallopOption[Flag]): ScallopOption[Boolean] =
    so.map(identity)

  def parseConf(
      arguments: Seq[String],
      defaults: ConfigurationSoft
  ): Either[String, ConfigurationSoft] = {
    val e = for {
      c <- parseCommand(arguments, defaults)
    } yield
      Try {
        val options = Options(arguments, defaults)
        val server = ConfigurationSoft.Server(
          options.run.serverHost,
          options.run.serverPort,
          options.run.serverHttpPort,
          options.run.serverKademliaPort,
          options.run.serverDynamicHostAddress,
          options.run.serverNoUpnp,
          options.run.serverDefaultTimeout,
          options.run.serverBootstrap,
          options.run.serverStandalone,
          options.run.serverMapSize,
          options.run.serverStoreType,
          options.run.serverDataDir,
          options.run.serverMaxNumOfConnections,
          c match {
            case _: Configuration.Command.Diagnostics.type =>
              options.diagnostics.serverMaxMessageSize
            case _: Configuration.Command.Run.type => options.run.serverMaxMessageSize
          },
          c match {
            case _: Configuration.Command.Diagnostics.type =>
              options.diagnostics.serverChunkSize
            case _: Configuration.Command.Run.type => options.run.serverChunkSize
          }
        )
        val grpcServer = ConfigurationSoft.GrpcServer(
          c match {
            case _: Configuration.Command.Diagnostics.type =>
              options.diagnostics.grpcHost
            case _: Configuration.Command.Run.type =>
              options.run.grpcHost
          },
          options.run.grpcSocket,
          c match {
            case _: Configuration.Command.Diagnostics.type =>
              options.diagnostics.grpcPortExternal
            case _: Configuration.Command.Run.type => options.run.grpcPortExternal
          },
          options.run.grpcPortInternal
        )
        val tls = ConfigurationSoft.Tls(
          options.run.tlsCertificate,
          options.run.tlsKey,
          options.run.tlsSecureRandomNonBlocking
        )
        val casper = ConfigurationSoft.Casper(
          options.run.casperValidatorPublicKey,
          options.run.casperValidatorPrivateKey,
          options.run.casperValidatorPrivateKeyPath,
          options.run.casperValidatorSigAlgorithm,
          options.run.casperBondsFile,
          options.run.casperKnownValidatorsFile,
          options.run.casperNumValidators,
          options.run.casperWalletsFile,
          options.run.casperMinimumBond,
          options.run.casperMaximumBond,
          options.run.casperHasFaucet,
          options.run.casperRequiredSigs,
          options.run.casperShardId,
          options.run.casperApproveGenesis,
          options.run.casperApproveGenesisInterval,
          options.run.casperApproveGenesisDuration,
          options.run.casperDeployTimestamp
        )

        val lmdb = ConfigurationSoft.LmdbBlockStore(
          options.run.lmdbBlockStoreSize,
          options.run.lmdbMaxDbs,
          options.run.lmdbMaxReaders,
          options.run.lmdbUseTls
        )

        val blockstorage = ConfigurationSoft.BlockDagFileStorage(
          options.run.blockstorageLatestMessagesLogMaxSizeFactor
        )

        val metrics = ConfigurationSoft.Metrics(
          options.run.metricsPrometheus,
          options.run.metricsZipkin,
          options.run.metricsSigar
        )

        val influx = ConfigurationSoft.Influx(
          options.run.influxHostname,
          options.run.influxPort,
          options.run.influxDatabase,
          options.run.influxProtocol
        )

        ConfigurationSoft(
          Some(server),
          Some(grpcServer),
          Some(tls),
          Some(casper),
          Some(lmdb),
          Some(blockstorage),
          Some(metrics),
          Some(influx),
          None
        )
      }.toEither.leftMap(_.getMessage)
    e.joinRight
  }

  def parseCommand(
      args: Seq[String],
      defaults: ConfigurationSoft
  ): Either[String, Configuration.Command] =
    Try {
      val options = Options(args, defaults)
      options.subcommand.fold(s"Command was not provided".asLeft[Configuration.Command]) {
        case options.run         => Configuration.Command.Run.asRight[String]
        case options.diagnostics => Configuration.Command.Run.asRight[String]
      }
    }.toEither.leftMap(_.getMessage).joinRight

  def tryReadConfigFile(
      args: Seq[String],
      defaults: ConfigurationSoft
  ): Option[Either[String, String]] =
    Options(args, defaults).configFile
      .map(p => Try(Source.fromFile(p.toFile).mkString).toEither.leftMap(_.getMessage))
      .toOption
}

private[configuration] final case class Options(
    arguments: Seq[String],
    defaultsForHelpPrinting: ConfigurationSoft
) extends ScallopConf(arguments) {
  helpWidth(120)
  import Converter._
  import Options.Flag

  implicit def optionToString[A](opt: Option[A]) =
    opt.fold(" No default.")(v => s" Default is '$v'.")

  //TODO: Use Monocle lenses?
  def s[A](select: ConfigurationSoft.Server => Option[A]): String =
    defaultsForHelpPrinting.server.flatMap(select)

  def g[A](select: ConfigurationSoft.GrpcServer => Option[A]): String =
    defaultsForHelpPrinting.grpc.flatMap(select)

  def t[A](select: ConfigurationSoft.Tls => Option[A]): String =
    defaultsForHelpPrinting.tls.flatMap(select)

  def c[A](select: ConfigurationSoft.Casper => Option[A]): String =
    defaultsForHelpPrinting.casper.flatMap(select)

  def l[A](select: ConfigurationSoft.LmdbBlockStore => Option[A]): String =
    defaultsForHelpPrinting.lmdb.flatMap(select)

  def b[A](select: ConfigurationSoft.BlockDagFileStorage => Option[A]): String =
    defaultsForHelpPrinting.blockstorage.flatMap(select)

  def m[A](select: ConfigurationSoft.Metrics => Option[A]): String =
    defaultsForHelpPrinting.metrics.flatMap(select)

  def i[A](select: ConfigurationSoft.Influx => Option[A]): String =
    defaultsForHelpPrinting.influx.flatMap(select)

  version(s"Casper Labs Node ${BuildInfo.version}")
  printedName = "casperlabs"
  banner(
    """
      |Configuration file --config-file can contain tables
      |[server], [grpc], [lmdb], [casper], [tls], [metrics], [influx] and [block-storage].
      |
      |CLI options match TOML keys and environment variables, example:
      |    --[prefix]-[key-name]=value e.g. --server-data-dir=/casperlabs
      |
      |    equals
      |
      |    [prefix]            [server]                  CL_[PREFIX]_[SNAKIFIED_KEY]
      |    key-name = "value"  data-dir = "/casperlabs"  CL_SERVER_DATA_DIR=/casperlabs
      |
      |Each option has a type listed in opt's description beginning that should be used in TOML file.
      |
      |CLI arguments will take precedence over environment variables.
      |environment variables will take precedence over TOML file.
    """.stripMargin
  )

  val configFile = opt[Path](descr = "String. Path to the TOML configuration file.")

  val diagnostics = new Subcommand("diagnostics") {
    helpWidth(120)
    descr("Node diagnostics")

    val grpcPortExternal =
      opt[Int](descr = s"Int. Port used for external gRPC API.${g(_.portExternal)}")

    val grpcHost =
      opt[String](
        descr = s"String. Hostname or IP of node on which gRPC service is running.${g(_.host)}"
      )

    val serverMaxMessageSize =
      opt[Int](
        descr =
          s"Int. Maximum size of message that can be sent via transport layer.${s(_.maxMessageSize)}"
      )

    val serverChunkSize =
      opt[Int](
        descr =
          s"Int. Size of chunks to split larger payloads into when streamed via transport layer.${s(_.chunkSize)}"
      )
  }
  addSubcommand(diagnostics)

  val run = new Subcommand("run") {
    helpWidth(120)

    val grpcPortExternal =
      opt[Int](descr = s"Int. Port used for external gRPC API.${g(_.portExternal)}")

    val grpcHost =
      opt[String](
        descr = s"String. Hostname or IP of node on which gRPC service is running.${g(_.host)}"
      )

    val serverMaxMessageSize =
      opt[Int](
        descr =
          s"Int. Maximum size of message that can be sent via transport layer.${s(_.maxMessageSize)}"
      )

    val serverChunkSize =
      opt[Int](
        descr =
          s"Int. Size of chunks to split larger payloads into when streamed via transport layer.${s(_.chunkSize)}"
      )

    val grpcPortInternal =
      opt[Int](descr = s"Int. Port used for internal gRPC API.${g(_.portInternal)}")

    val grpcSocket =
      opt[Path](descr = s"String. Socket path used for internal gRPC API.${g(_.socket)}")

    val serverDynamicHostAddress =
      opt[Flag](descr = s"Boolean. Host IP address changes dynamically.${s(_.dynamicHostAddress)}")

    val serverNoUpnp = opt[Flag](descr = s"Boolean. Use this flag to disable UpNp.${s(_.noUpnp)}")

    val serverDefaultTimeout =
      opt[Int](
        descr = s"Int. Default timeout for roundtrip connections.${s(_.defaultTimeout)}"
      )

    val tlsCertificate =
      opt[Path](
        short = 'c',
        descr =
          s"String. Path to node's X.509 certificate file, that is being used for identification.${t(_.certificate)}"
      )

    val tlsKey =
      opt[Path](
        short = 'k',
        descr =
          s"String. Path to node's private key PEM file, that is being used for TLS communication.${t(_.key)}"
      )

    val tlsSecureRandomNonBlocking =
      opt[Flag](
        descr =
          s"Boolean. Use a non blocking secure random instance.${t(_.secureRandomNonBlocking)}"
      )

    val serverPort =
      opt[Int](short = 'p', descr = s"Int. Network port to use.${s(_.port)}")

    val serverHttpPort =
      opt[Int](
        descr =
          s"Int. HTTP port (deprecated - all API features will be ported to gRPC API).${s(_.httpPort)}"
      )

    val serverKademliaPort =
      opt[Int](
        descr =
          s"Int. Kademlia port used for node discovery based on Kademlia algorithm.${s(_.kademliaPort)}"
      )

    val casperNumValidators =
      opt[Int](descr = s"Int of validators at genesis.${c(_.numValidators)}")

    val casperBondsFile = opt[Path](
      descr = "String. Path to plain text file consisting of lines of the form `<pk> <stake>`, " +
        "which defines the bond amounts for each validator at genesis. " +
        "<pk> is the public key (in base-16 encoding) identifying the validator and <stake>" +
        s"is the amount of Rev they have bonded (an integer). Note: this overrides the --num-validators option.${c(_.bondsFile)}"
    )
    val casperKnownValidatorsFile = opt[String](
      descr = "String. Path to plain text file listing the public keys of validators known to the user (one per line). " +
        "Signatures from these validators are required in order to accept a block which starts the local" +
        s"node's view of the blockDAG.${c(_.knownValidatorsFile)}"
    )
    val casperWalletsFile = opt[Path](
      descr = "String. Path to plain text file consisting of lines of the form `<algorithm> <pk> <revBalance>`, " +
        "which defines the Rev wallets that exist at genesis. " +
        "<algorithm> is the algorithm used to verify signatures when using the wallet (one of ed25519 or secp256k1)," +
        "<pk> is the public key (in base-16 encoding) identifying the wallet and <revBalance>" +
        s"is the amount of Rev in the wallet.${c(_.walletsFile)}"
    )
    val casperMinimumBond = opt[Long](
      descr =
        s"Long. Minimum bond accepted by the PoS contract in the genesis block.${c(_.minimumBond)}"
    )
    val casperMaximumBond = opt[Long](
      descr =
        s"Long. Maximum bond accepted by the PoS contract in the genesis block.${c(_.maximumBond)}"
    )
    val casperHasFaucet = opt[Flag](
      descr =
        s"Boolean. True if there should be a public access Rev faucet in the genesis block.${c(_.hasFaucet)}"
    )

    val serverBootstrap =
      opt[PeerNode](
        short = 'b',
        descr = s"String. Bootstrap casperlabs node address for initial seed.${s(_.bootstrap)}"
      )

    val serverStandalone =
      opt[Flag](
        short = 's',
        descr = s"Boolean. Start a stand-alone node (no bootstrapping).${s(_.standalone)}"
      )

    val casperRequiredSigs =
      opt[Int](
        descr =
          s"Int of signatures from trusted validators required to creating an approved genesis block.${c(_.requiredSigs)}"
      )

    val casperDeployTimestamp =
      opt[Long](
        descr = s"Long. Timestamp for the deploys.${c(_.deployTimestamp)}"
      )

    val casperApproveGenesisDuration =
      opt[FiniteDuration](
        short = 'd',
        descr =
          s"String. Time window in which BlockApproval messages will be accumulated before checking conditions.${c(_.approveGenesisDuration)}"
      )

    val casperApproveGenesisInterval =
      opt[FiniteDuration](
        short = 'i',
        descr =
          s"String. Interval at which condition for creating ApprovedBlock will be checked.${c(_.approveGenesisInterval)}"
      )

    val casperApproveGenesis =
      opt[Flag](descr = s"Boolean. Start a node as a genesis validator.${c(_.approveGenesis)}")

    val serverHost = opt[String](descr = s"String. Hostname or IP of this node.${s(_.host)}")

    val serverDataDir =
      opt[Path](required = false, descr = s"String. Path to data directory. ${s(_.dataDir)}")

    val serverMapSize =
      opt[Long](required = false, descr = s"Long. Map size (in bytes).${s(_.mapSize)}")

    val serverStoreType =
      opt[StoreType](
        required = false,
        descr = s"String. Type of Casperlabs space backing store.${s(_.storeType)}"
      )

    val serverMaxNumOfConnections =
      opt[Int](
        descr =
          s"Int. Maximum number of peers allowed to connect to the node.${s(_.maxNumOfConnections)}"
      )

    val lmdbBlockStoreSize =
      opt[Long](
        required = false,
        descr = s"Long. Casper BlockStore map size (in bytes).${l(_.blockStoreSize)}"
      )

    val lmdbMaxDbs =
      opt[Int](
        required = false,
        descr = s"Int. LMDB max databases.${l(_.maxDbs)}"
      )

    val lmdbMaxReaders =
      opt[Int](
        required = false,
        descr = s"Int. LMDB max readers.${l(_.maxReaders)}"
      )

    val lmdbUseTls =
      opt[Flag](
        required = false,
        descr = s"Boolean. LMDB use TLS.${l(_.useTls)}"
      )

    val blockstorageLatestMessagesLogMaxSizeFactor =
      opt[Int](
        descr =
          s"Int. Size factor for squashing block storage latest messages.${b(_.latestMessagesLogMaxSizeFactor)}"
      )

    val casperValidatorPublicKey = opt[String](
      descr = "String. Base16 encoding of the public key to use for signing a proposed blocks. " +
        s"Can be inferred from the private key for some signature algorithms.${c(_.validatorPublicKey)}"
    )

    val casperValidatorPrivateKey = opt[String](
      descr = "String. Base16 encoding of the private key to use for signing a proposed blocks. " +
        s"It is not recommended to use in production since private key could be revealed through the process table.${c(_.validatorPrivateKey)}"
    )

    val casperValidatorPrivateKeyPath = opt[Path](
      descr =
        s"String. Path to the base16 encoded private key to use for signing a proposed blocks.${c(_.validatorPrivateKeyPath)}"
    )

    val casperValidatorSigAlgorithm = opt[String](
      descr = "String. Name of the algorithm to use for signing proposed blocks. " +
        s"Currently supported values: ed25519.${c(_.validatorSigAlgorithm)}"
    )

    val casperShardId = opt[String](
      descr = s"String. Identifier of the shard this node is connected to.${c(_.shardId)}"
    )

    val metricsPrometheus =
      opt[Flag](descr = s"Boolean. Enable the Prometheus metrics reporter.${m(_.prometheus)}")

    val metricsZipkin =
      opt[Flag](descr = s"Boolean. Enable the Zipkin span reporter.${m(_.zipkin)}")

    val metricsSigar =
      opt[Flag](descr = s"Boolean. Enable Sigar host system metrics.${m(_.sigar)}")

    val influxHostname =
      opt[String](descr = s"String. Hostname or IP of the Influx instance.${i(_.hostname)}")

    val influxDatabase =
      opt[String](descr = s"String. Name of the database in Influx.${i(_.database)}")

    val influxPort =
      opt[Int](descr = s"Int. Port of the Influx instance.${i(_.port)}")

    val influxProtocol =
      opt[String](descr = s"String. Protocol used in Influx.${i(_.protocol)}")
  }
  addSubcommand(run)

  val hexCheck: String => Boolean     = _.matches("[0-9a-fA-F]+")
  val addressCheck: String => Boolean = addr => addr.startsWith("0x") && hexCheck(addr.drop(2))

  val bondingDeployGen = new Subcommand("generateBondingDeploys") {
    descr(
      "Creates the rholang source files needed for bonding assuming you have a " +
        "pre-wallet from the REV issuance. These files must be" +
        "deployed to a node operated by a presently bonded validator. The rho files" +
        "are created in the working directory where the command is executed. Note: " +
        "for security reasons it is best to deploy `unlock*.rho` and `forward*.rho` first" +
        "and `bond*.rho` in a separate block after those (i.e. only deploy `bond*.rho` " +
        "after `unlock*.rho` and `forward*.rho` have safely been included in a propsed block)."
    )

    val ethAddr = opt[String](
      descr = "Ethereum address associated with the \"pre-wallet\" to bond.",
      validate = addressCheck,
      required = true
    )

    val bondKey = opt[String](
      descr = "Hex-encoded public key which will be used as the validator idenity after bonding. " +
        "Note: as of this version of node this must be an ED25519 key.",
      validate = hexCheck,
      required = true
    )

    val amount = opt[Long](
      descr = "The amount of REV to bond. Must be less than or equal to the wallet balance.",
      validate = _ > 0,
      required = true
    )

    val publicKey = opt[String](
      descr = "Hex-encoded public key associated with the Ethereum address of the pre-wallet.",
      validate = hexCheck,
      required = true
    )

    val privateKey = opt[String](
      descr = "Hex-encoded private key associated with the Ethereum address of the pre-wallet.",
      validate = hexCheck,
      required = true
    )
  }
  addSubcommand(bondingDeployGen)

  val faucetBondingDeployGen = new Subcommand("generateFaucetBondingDeploys") {
    descr(
      "Creates the rholang source files needed for bonding by making use of " +
        "test net faucet. These files must be" +
        "deployed to a node operated by a presently bonded validator. The rho files" +
        "are created in the working directory where the command is executed. Note: " +
        "for security reasons it is best to deploy `forward*.rho` first" +
        "and then `bond*.rho` in a separate block afterwards (i.e. only deploy `bond*.rho` " +
        "after `forward*.rho` has safely been included in a propsed block)."
    )

    val amount = opt[Long](
      descr = "The amount of REV to bond. Must be less than or equal to the wallet balance.",
      validate = _ > 0,
      required = true
    )

    val sigAlgorithm = opt[String](
      descr =
        "Signature algorithm to be used with the provided keys. Must be one of ed25519 or secp256k1.",
      validate = (s: String) => { s == "ed25519" || s == "secp256k1" },
      required = true
    )

    val publicKey = opt[String](
      descr = "Hex-encoded public key to be used as the validator id when bonding.",
      validate = hexCheck,
      required = true
    )

    val privateKey = opt[String](
      descr = "Hex-encoded private key associated with the supplied public key.",
      validate = hexCheck,
      required = true
    )
  }
  addSubcommand(faucetBondingDeployGen)

  verify()
}
