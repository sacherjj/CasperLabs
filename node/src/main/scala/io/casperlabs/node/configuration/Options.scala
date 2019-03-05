package io.casperlabs.node.configuration

import java.nio.file.Path

import cats.syntax.either._
import cats.syntax.option._
import io.casperlabs.comm.PeerNode
import io.casperlabs.node.BuildInfo
import io.casperlabs.shared.{scallop, StoreType}
import org.rogach.scallop._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.io.Source
import scala.language.implicitConversions
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
      defaults: Map[String, String]
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
          server,
          grpcServer,
          tls,
          casper,
          lmdb,
          blockstorage,
          metrics,
          influx,
          ConfigurationSoft.InfluxAuth(
            None,
            None
          )
        )
      }.toEither.leftMap(_.getMessage)
    e.joinRight
  }

  def parseCommand(
      args: Seq[String],
      defaults: Map[String, String]
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
      defaults: Map[String, String]
  ): Option[Either[String, String]] =
    Options(args, defaults).configFile
      .map(p => Try(Source.fromFile(p.toFile).mkString).toEither.leftMap(_.getMessage))
      .toOption
}

//noinspection TypeAnnotation
private[configuration] final case class Options(
    arguments: Seq[String],
    defaults: Map[String, String]
) extends ScallopConf(arguments) {
  helpWidth(120)
  import Converter._
  import Options.Flag

  //Needed only for eliminating red code from IntelliJ IDEA, see @scallop definition
  private def gen[A](descr: String, short: Char = '\u0000'): ScallopOption[A] =
    sys.error("Add @scallop macro annotation")

  version(s"Casper Labs Node ${BuildInfo.version}")
  printedName = "casperlabs"
  banner(
    """
      |Configuration file --config-file can contain tables
      |[server], [grpc], [lmdb], [casper], [tls], [metrics], [influx] and [blockstorage].
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

  @scallop
  val configFile =
    gen[Path]("Path to the TOML configuration file.")

  val diagnostics = new Subcommand("diagnostics") { self =>
    helpWidth(120)
    descr("Node diagnostics")

    @scallop
    val grpcPortExternal =
      gen[Int]("Port used for external gRPC API, e.g. deployments.")

    @scallop
    val grpcHost =
      gen[String]("Externally addressable hostname or IP of node on which gRPC service is running.")

    @scallop
    val serverMaxMessageSize =
      gen[Int]("Maximum size of message that can be sent via transport layer.")

    @scallop
    val serverChunkSize =
      gen[Int]("Size of chunks to split larger payloads into when streamed via transport layer.")
  }
  addSubcommand(diagnostics)

  val run = new Subcommand("run") {
    helpWidth(120)

    @scallop
    val grpcPortExternal =
      gen[Int]("Port used for external gRPC API, e.g. deployments.")

    @scallop
    val grpcHost =
      gen[String]("Externally addressable hostname or IP of node on which gRPC service is running.")

    @scallop
    val serverMaxMessageSize =
      gen[Int]("Maximum size of message that can be sent via transport layer.")

    @scallop
    val serverChunkSize =
      gen[Int]("Size of chunks to split larger payloads into when streamed via transport layer.")

    @scallop
    val grpcPortInternal =
      gen[Int]("Port used for internal gRPC API, e.g. diagnostics.")

    @scallop
    val grpcSocket =
      gen[Path]("Socket path used for internal gRPC API.")

    @scallop
    val serverDynamicHostAddress =
      gen[Flag]("Host IP address changes dynamically.")

    @scallop
    val serverNoUpnp =
      gen[Flag]("Use this flag to disable UpNp.")

    @scallop
    val serverDefaultTimeout =
      gen[Int](
        "Default timeout for roundtrip connections."
      )

    @scallop
    val tlsCertificate =
      gen[Path](
        "Path to node's X.509 certificate file, that is being used for identification.",
        'c'
      )

    @scallop
    val tlsKey =
      gen[Path](
        "Path to node's private key PEM file, that is being used for TLS communication.",
        'k'
      )

    @scallop
    val tlsSecureRandomNonBlocking =
      gen[Flag](
        "Use a non blocking secure random instance."
      )

    @scallop
    val serverPort =
      gen[Int]("Network port to use for intra-node gRPC communication.", 'p')

    @scallop
    val serverHttpPort =
      gen[Int]("HTTP port for utility services: /metrics, /version and /status.")

    @scallop
    val serverKademliaPort =
      gen[Int](
        "Kademlia port used for node discovery based on Kademlia algorithm."
      )

    @scallop
    val casperNumValidators =
      gen[Int](
        "Amount of random validator keys to generate at genesis if no `bonds.txt` file is present."
      )

    @scallop
    val casperBondsFile =
      gen[Path](
        "Path to plain text file consisting of lines of the form `<pk> <stake>`, " +
          "which defines the bond amounts for each validator at genesis. " +
          "<pk> is the public key (in base-16 encoding) identifying the validator and <stake>" +
          s"is the amount of CSPR they have bonded (an integer). Note: this overrides the --num-validators option."
      )
    @scallop
    val casperKnownValidatorsFile =
      gen[String](
        "Path to plain text file listing the public keys of validators known to the user (one per line). " +
          "Signatures from these validators are required in order to accept a block which starts the local" +
          s"node's view of the blockDAG."
      )
    @scallop
    val casperWalletsFile =
      gen[Path](
        "Path to plain text file consisting of lines of the form `<algorithm> <pk> <revBalance>`, " +
          "which defines the CSPR wallets that exist at genesis. " +
          "<algorithm> is the algorithm used to verify signatures when using the wallet (one of ed25519 or secp256k1)," +
          "<pk> is the public key (in base-16 encoding) identifying the wallet and <revBalance>" +
          s"is the amount of CSPR in the wallet."
      )
    @scallop
    val casperMinimumBond =
      gen[Long]("Minimum bond accepted by the PoS contract in the genesis block.")
    @scallop
    val casperMaximumBond =
      gen[Long]("Maximum bond accepted by the PoS contract in the genesis block.")
    @scallop
    val casperHasFaucet =
      gen[Flag]("True if there should be a public access CSPR faucet in the genesis block.")

    @scallop
    val serverBootstrap =
      gen[PeerNode](
        "Bootstrap casperlabs node address for initial seed.",
        'b'
      )

    @scallop
    val serverStandalone =
      gen[Flag](
        "Start a stand-alone node (no bootstrapping).",
        's'
      )

    @scallop
    val casperRequiredSigs =
      gen[Int](
        "Number of signatures from trusted validators required to creating an approved genesis block."
      )

    @scallop
    val casperDeployTimestamp =
      gen[Long]("Timestamp for the deploys.")

    @scallop
    val casperApproveGenesisDuration =
      gen[FiniteDuration](
        "Time window in which BlockApproval messages will be accumulated before checking conditions.",
        'd'
      )

    @scallop
    val casperApproveGenesisInterval =
      gen[FiniteDuration](
        "Interval at which condition for creating ApprovedBlock will be checked.",
        'i'
      )

    @scallop
    val casperApproveGenesis =
      gen[Flag]("Start a node as a genesis validator.")

    @scallop
    val serverHost =
      gen[String]("Hostname or IP of this node.")

    @scallop
    val serverDataDir =
      gen[Path]("Path to data directory. ")

    @scallop
    val serverStoreType =
      gen[StoreType](
        s"Type of Casperlabs space backing store. Valid values are: ${StoreType.values.mkString(",")}"
      )

    @scallop
    val serverMaxNumOfConnections =
      gen[Int]("Maximum number of peers allowed to connect to the node.")
    @scallop
    val lmdbBlockStoreSize =
      gen[Long]("Casper BlockStore map size (in bytes).")

    @scallop
    val lmdbMaxDbs =
      gen[Int]("LMDB max databases.")

    @scallop
    val lmdbMaxReaders =
      gen[Int]("LMDB max readers.")

    @scallop
    val lmdbUseTls =
      gen[Flag]("LMDB use TLS.")

    @scallop
    val blockstorageLatestMessagesLogMaxSizeFactor =
      gen[Int]("Size factor for squashing block storage latest messages.")

    @scallop
    val casperValidatorPublicKey =
      gen[String](
        "Base16 encoding of the public key to use for signing a proposed blocks. " +
          s"Can be inferred from the private key for some signature algorithms."
      )

    @scallop
    val casperValidatorPrivateKey =
      gen[String](
        "Base16 encoding of the private key to use for signing a proposed blocks. " +
          s"It is not recommended to use in production since private key could be revealed through the process table." +
          "Use the `validator-private-key-path` instead."
      )

    @scallop
    val casperValidatorPrivateKeyPath =
      gen[Path]("Path to the base16 encoded private key to use for signing a proposed blocks.")

    @scallop
    val casperValidatorSigAlgorithm =
      gen[String](
        "Name of the algorithm to use for signing proposed blocks. " +
          s"Currently supported values: ed25519."
      )

    @scallop
    val casperShardId =
      gen[String](s"Identifier of the shard this node is connected to.")

    @scallop
    val metricsPrometheus =
      gen[Flag]("Enable the Prometheus metrics reporter.")

    @scallop
    val metricsZipkin =
      gen[Flag]("Enable the Zipkin span reporter.")

    @scallop
    val metricsSigar =
      gen[Flag]("Enable Sigar host system metrics.")

    @scallop
    val influxHostname =
      gen[String]("Hostname or IP of the Influx instance.")

    @scallop
    val influxDatabase =
      gen[String]("Name of the database in Influx.")

    @scallop
    val influxPort =
      gen[Int]("Port of the Influx instance.")

    @scallop
    val influxProtocol =
      gen[String]("Protocol used in Influx.")
  }
  addSubcommand(run)

  verify()
}
