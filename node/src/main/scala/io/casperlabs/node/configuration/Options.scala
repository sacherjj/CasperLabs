package io.casperlabs.node.configuration

import java.nio.file.Path

import io.casperlabs.comm.PeerNode
import io.casperlabs.node.BuildInfo
import io.casperlabs.shared.StoreType
import org.rogach.scallop._
import cats.syntax.either._
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
            .getOrElse(Left("can't parse the rnode bootstrap address"))
        case Nil => Right(None)
        case _   => Left("provide the rnode bootstrap address")
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

  def parseConf(arguments: Seq[String]): Either[String, ConfigurationSoft] =
    Try {
      val options = Options(arguments, None)
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
        options.serverMaxMessageSize
      )
      val grpcServer = ConfigurationSoft.GrpcServer(
        options.grpcHost,
        options.grpcPort,
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
        options.run.casperKnownValidators,
        options.run.casperNumValidators,
        options.run.casperGenesisPath,
        options.run.casperWalletsFile,
        options.run.casperMinimumBond,
        options.run.casperMaximumBond,
        options.run.casperHasFaucet,
        options.run.casperRequiredSigs,
        options.run.casperShardId,
        options.run.casperGenesisValidator,
        options.run.casperInterval,
        options.run.casperDuration,
        options.run.casperDeployTimestamp
      )
      val lmdb = ConfigurationSoft.LmdbBlockStore(
        options.run.lmdbPath,
        options.run.lmdbBlockStoreSize,
        options.run.lmdbMaxDbs,
        options.run.lmdbMaxReaders,
        options.run.lmdbUseTls
      )

      val blockStorage = ConfigurationSoft.BlockDagFileStorage(
        options.run.blockStorageLatestMessagesLogPath,
        options.run.blockStorageLatestMessagesCrcPath,
        options.run.blockStorageBlockMetadataLogPath,
        options.run.blockStorageBlockMetadataCrcPath,
        options.run.blockStorageCheckpointsDirPath,
        options.run.blockStorageLatestMessagesLogMaxSizeFactor
      )
      ConfigurationSoft(
        Some(server),
        Some(grpcServer),
        Some(tls),
        Some(casper),
        Some(lmdb),
        Some(blockStorage)
      )
    }.toEither.leftMap(_.getMessage)

  def parseCommand(args: Seq[String]): Either[String, Configuration.Command] =
    Try {
      val options = Options(args, None)
      options.subcommand.fold(s"Command was not provided".asLeft[Configuration.Command]) {
        case options.run         => Configuration.Command.Run.asRight[String]
        case options.diagnostics => Configuration.Command.Run.asRight[String]
      }
    }.toEither.leftMap(_.getMessage).joinRight

  def tryReadConfigFile(args: Seq[String]): Option[Either[String, String]] =
    Options(args, None).configFile
      .map(p => Try(Source.fromFile(p.toFile).mkString).toEither.leftMap(_.getMessage))
      .toOption

  def printHelp(defaults: ConfigurationSoft): Unit = Options(Seq.empty, Some(defaults)).printHelp()
}

private[configuration] final case class Options(
    arguments: Seq[String],
    defaultsForHelpPrinting: Option[ConfigurationSoft]
) extends ScallopConf(arguments) {
  import Converter._
  import Options.Flag

  implicit def optionToString[A](opt: Option[A]) =
    opt.fold(" No default.")(v => s" Default is '$v'.")

  //TODO: Use Monocle lenses?
  def s[A](select: ConfigurationSoft.Server => Option[A]): String =
    defaultsForHelpPrinting.flatMap(_.server.flatMap(select))

  def g[A](select: ConfigurationSoft.GrpcServer => Option[A]): String =
    defaultsForHelpPrinting.flatMap(_.grpcServer.flatMap(select))

  def t[A](select: ConfigurationSoft.Tls => Option[A]): String =
    defaultsForHelpPrinting.flatMap(_.tls.flatMap(select))

  def c[A](select: ConfigurationSoft.Casper => Option[A]): String =
    defaultsForHelpPrinting.flatMap(_.casper.flatMap(select))

  def l[A](select: ConfigurationSoft.LmdbBlockStore => Option[A]): String =
    defaultsForHelpPrinting.flatMap(_.lmdb.flatMap(select))

  def b[A](select: ConfigurationSoft.BlockDagFileStorage => Option[A]): String =
    defaultsForHelpPrinting.flatMap(_.blockStorage.flatMap(select))

  version(s"Casper Labs Node ${BuildInfo.version}")
  printedName = "casperlabs"

  val configFile = opt[Path](descr = "Path to the configuration file.")

  val grpcPort =
    opt[Int](descr = s"Port used for external gRPC API.${g(_.portExternal)}")

  val grpcHost =
    opt[String](descr = s"Hostname or IP of node on which gRPC service is running.${g(_.host)}")

  val serverMaxMessageSize =
    opt[Int](
      descr = s"Maximum size of message that can be sent via transport layer.${s(_.maxMessageSize)}"
    )

  val diagnostics = new Subcommand("diagnostics") {
    descr("Node diagnostics")
  }
  addSubcommand(diagnostics)

  val run = new Subcommand("run") {

    val grpcPortInternal =
      opt[Int](descr = s"Port used for internal gRPC API.${g(_.portInternal)}")

    val serverDynamicHostAddress =
      opt[Flag](descr = s"Host IP address changes dynamically.${s(_.dynamicHostAddress)}")

    val serverNoUpnp = opt[Flag](descr = s"Use this flag to disable UpNp.${s(_.noUpnp)}")

    val serverDefaultTimeout =
      opt[Int](
        descr = s"Default timeout for roundtrip connections.${s(_.defaultTimeout)}"
      )

    val tlsCertificate =
      opt[Path](
        short = 'c',
        descr =
          s"Path to node's X.509 certificate file, that is being used for identification.${t(_.certificate)}"
      )

    val tlsKey =
      opt[Path](
        short = 'k',
        descr =
          s"Path to node's private key PEM file, that is being used for TLS communication.${t(_.key)}"
      )

    val tlsSecureRandomNonBlocking =
      opt[Flag](
        descr = s"Use a non blocking secure random instance.${t(_.secureRandomNonBlocking)}"
      )

    val serverPort =
      opt[Int](short = 'p', descr = s"Network port to use.${s(_.port)}")

    val serverHttpPort =
      opt[Int](
        descr =
          s"HTTP port (deprecated - all API features will be ported to gRPC API).${s(_.httpPort)}"
      )

    val serverKademliaPort =
      opt[Int](
        descr =
          s"Kademlia port used for node discovery based on Kademlia algorithm.${s(_.kademliaPort)}"
      )

    val casperNumValidators =
      opt[Int](descr = s"Number of validators at genesis.${c(_.numValidators)}")
    val casperBondsFile = opt[String](
      descr = "Plain text file consisting of lines of the form `<pk> <stake>`, " +
        "which defines the bond amounts for each validator at genesis. " +
        "<pk> is the public key (in base-16 encoding) identifying the validator and <stake>" +
        s"is the amount of Rev they have bonded (an integer). Note: this overrides the --num-validators option.${c(_.bondsFile)}"
    )
    val casperKnownValidators = opt[String](
      descr = "Plain text file listing the public keys of validators known to the user (one per line). " +
        "Signatures from these validators are required in order to accept a block which starts the local" +
        s"node's view of the blockDAG.${c(_.knownValidatorsFile)}"
    )
    val casperWalletsFile = opt[String](
      descr = "Plain text file consisting of lines of the form `<algorithm> <pk> <revBalance>`, " +
        "which defines the Rev wallets that exist at genesis. " +
        "<algorithm> is the algorithm used to verify signatures when using the wallet (one of ed25519 or secp256k1)," +
        "<pk> is the public key (in base-16 encoding) identifying the wallet and <revBalance>" +
        s"is the amount of Rev in the wallet.${c(_.walletsFile)}"
    )
    val casperMinimumBond = opt[Long](
      descr = s"Minimum bond accepted by the PoS contract in the genesis block.${c(_.minimumBond)}"
    )
    val casperMaximumBond = opt[Long](
      descr = s"Maximum bond accepted by the PoS contract in the genesis block.${c(_.maximumBond)}"
    )
    val casperHasFaucet = opt[Flag](
      descr =
        s"True if there should be a public access Rev faucet in the genesis block.${c(_.hasFaucet)}"
    )

    val casperGenesisPath = opt[Path](
      descr = s"TODO.${c(_.genesisPath)}"
    )

    val serverBootstrap =
      opt[PeerNode](
        short = 'b',
        descr = s"Bootstrap rnode address for initial seed.${s(_.bootstrap)}"
      )

    val serverStandalone =
      opt[Flag](
        short = 's',
        descr = s"Start a stand-alone node (no bootstrapping).${s(_.standalone)}"
      )

    val casperRequiredSigs =
      opt[Int](
        descr =
          s"Number of signatures from trusted validators required to creating an approved genesis block.${c(_.requiredSigs)}"
      )

    val casperDeployTimestamp =
      opt[Long](
        descr = s"Timestamp for the deploys.${c(_.deployTimestamp)}"
      )

    val casperDuration =
      opt[FiniteDuration](
        short = 'd',
        descr =
          s"Time window in which BlockApproval messages will be accumulated before checking conditions.${c(_.approveGenesisDuration)}"
      )

    val casperInterval =
      opt[FiniteDuration](
        short = 'i',
        descr =
          s"Interval at which condition for creating ApprovedBlock will be checked.${c(_.approveGenesisInterval)}"
      )

    val casperGenesisValidator =
      opt[Flag](descr = s"Start a node as a genesis validator.${c(_.approveGenesis)}")

    val serverHost = opt[String](descr = s"Hostname or IP of this node.${s(_.host)}")

    val serverDataDir =
      opt[Path](required = false, descr = s"Path to data directory. ${s(_.dataDir)}")

    val serverMapSize = opt[Long](required = false, descr = s"Map size (in bytes).${s(_.mapSize)}")

    val serverStoreType =
      opt[StoreType](required = false, descr = s"Type of RSpace backing store.${s(_.storeType)}")

    val serverMaxNumOfConnections =
      opt[Int](
        descr =
          s"Maximum number of peers allowed to connect to the node.${s(_.maxNumOfConnections)}"
      )

    val lmdbBlockStoreSize =
      opt[Long](
        required = false,
        descr = s"Casper BlockStore map size (in bytes).${l(_.blockStoreSize)}"
      )

    val lmdbPath = opt[Path](descr = s"TODO.${l(_.path)}")

    val lmdbMaxDbs = opt[Int](descr = s"TODO.${l(_.maxDbs)}")

    val lmdbMaxReaders = opt[Int](descr = s"TODO.${l(_.maxReaders)}")

    val lmdbUseTls = opt[Flag](descr = s"TODO.${l(_.useTls)}")

    val casperValidatorPublicKey = opt[String](
      descr = "Base16 encoding of the public key to use for signing a proposed blocks. " +
        s"Can be inferred from the private key for some signature algorithms.${c(_.publicKey)}"
    )

    val casperValidatorPrivateKey = opt[String](
      descr = "Base16 encoding of the private key to use for signing a proposed blocks. " +
        s"It is not recommended to use in production since private key could be revealed through the process table.${c(_.privateKey)}"
    )

    val casperValidatorPrivateKeyPath = opt[Path](
      descr =
        s"Path to the base16 encoded private key to use for signing a proposed blocks.${c(_.privateKeyPath)}"
    )

    val casperValidatorSigAlgorithm = opt[String](
      descr = "Name of the algorithm to use for signing proposed blocks. " +
        s"Currently supported values: ed25519.${c(_.sigAlgorithm)}"
    )

    val casperShardId = opt[String](
      descr = s"Identifier of the shard this node is connected to.${c(_.shardId)}"
    )
    val blockStorageLatestMessagesLogPath = opt[Path](descr = s"TODO.${b(_.latestMessagesLogPath)}")

    val blockStorageLatestMessagesCrcPath = opt[Path](descr = s"TODO.${b(_.latestMessagesCrcPath)}")

    val blockStorageBlockMetadataLogPath = opt[Path](descr = s"TODO.${b(_.blockMetadataLogPath)}")

    val blockStorageBlockMetadataCrcPath = opt[Path](descr = s"TODO.${b(_.blockMetadataCrcPath)}")

    val blockStorageCheckpointsDirPath = opt[Path](descr = s"TODO.${b(_.checkpointsDirPath)}")

    val blockStorageLatestMessagesLogMaxSizeFactor =
      opt[Int](descr = s"TODO.${b(_.latestMessagesLogMaxSizeFactor)}")

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
