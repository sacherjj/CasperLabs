package io.casperlabs.node.configuration

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit

import cats.data.Validated.Valid
import cats.syntax.option._
import cats.syntax.show._
import com.google.protobuf.ByteString
import eu.timepit.refined.auto._
import io.casperlabs.casper.CasperConf
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeIdentifier}
import io.casperlabs.comm.transport.Tls
import io.casperlabs.configuration.ignore
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.node.configuration.Utils._
import org.scalacheck.Shrink
import org.scalacheck.ScalacheckShapeless._
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.concurrent.duration._

class ConfigurationSpec
    extends FunSuite
    with Matchers
    with BeforeAndAfterEach
    with GeneratorDrivenPropertyChecks
    with ArbitraryImplicits
    with ParserImplicits {

  val configFilename: String = s"test-configuration.toml"

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 100,
      workers = 1
    )

  // Random value generators for refined types are in ArbitraryImplicits.
  val defaultConf: Configuration = {
    val server = Configuration.Server(
      host = "test".some,
      port = 1,
      httpPort = 1,
      kademliaPort = 1,
      dynamicHostAddress = false,
      noUpnp = false,
      defaultTimeout = FiniteDuration(1, TimeUnit.SECONDS),
      bootstrap = List(
        NodeWithoutChainId(
          Node(
            NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
            "52.119.8.109",
            1,
            1,
            ByteString.EMPTY
          )
        ),
        NodeWithoutChainId(
          Node(
            NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
            "127.0.0.1",
            1,
            1,
            ByteString.EMPTY
          )
        )
      ),
      dataDir = Paths.get("/tmp"),
      maxNumOfConnections = 1,
      maxMessageSize = 1,
      eventStreamBufferSize = 1,
      engineParallelism = 1,
      chunkSize = 1,
      relayFactor = 1,
      relaySaturation = 1,
      approvalRelayFactor = 1,
      approvalPollInterval = FiniteDuration(1, TimeUnit.SECONDS),
      alivePeersCacheExpirationPeriod = FiniteDuration(1, TimeUnit.SECONDS),
      syncMaxPossibleDepth = 1,
      syncMinBlockCountToCheckWidth = 1,
      syncMaxBondingRate = 1.0,
      syncMaxDepthAncestorsRequest = 1,
      initSyncMaxNodes = 1,
      initSyncMinSuccessful = 1,
      initSyncMemoizeNodes = false,
      initSyncSkipFailedNodes = false,
      initSyncStep = 1,
      initSyncRoundPeriod = FiniteDuration(1, TimeUnit.SECONDS),
      initSyncMaxBlockCount = 1,
      periodicSyncRoundPeriod = FiniteDuration(1, TimeUnit.SECONDS),
      downloadMaxParallelBlocks = 1,
      downloadMaxRetries = 1,
      downloadRetryInitialBackoffPeriod = FiniteDuration(1, TimeUnit.SECONDS),
      downloadRetryBackoffFactor = 1.0,
      relayMaxParallelBlocks = 1,
      relayBlockChunkConsumerTimeout = FiniteDuration(1, TimeUnit.SECONDS),
      cleanBlockStorage = false,
      blockUploadRateMaxRequests = 0,
      blockUploadRatePeriod = Duration.Zero,
      blockUploadRateMaxThrottled = 0
    )
    val grpcServer = Configuration.Grpc(
      socket = Paths.get("/tmp/test"),
      portExternal = 1,
      portInternal = 1,
      useTls = false
    )
    val casper = CasperConf(
      validatorPublicKey = "test".some,
      validatorPublicKeyPath = Paths.get("/tmp/test").some,
      validatorPrivateKey = "test".some,
      validatorPrivateKeyPath = Paths.get("/tmp/test").some,
      validatorSigAlgorithm = "test",
      knownValidatorsFile = Paths.get("/tmp/test").some,
      requiredSigs = 1,
      chainSpecPath = Paths.get("/tmp/test").some,
      standalone = false,
      autoProposeEnabled = false,
      autoProposeCheckInterval = FiniteDuration(1, TimeUnit.SECONDS),
      autoProposeBallotInterval = FiniteDuration(1, TimeUnit.SECONDS),
      autoProposeAccInterval = FiniteDuration(1, TimeUnit.SECONDS),
      autoProposeAccCount = 1,
      maxBlockSizeBytes = 1,
      minTtl = FiniteDuration(1, TimeUnit.HOURS)
    )
    val tls = Tls(
      certificate = Paths.get("/tmp/test.crt"),
      key = Paths.get("/tmp/test.key"),
      apiCertificate = Paths.get("/tmp/test.api.crt"),
      apiKey = Paths.get("/tmp/test.api.key")
    )
    val blockStorage = Configuration.BlockStorage(
      cacheMaxSizeBytes = 1,
      cacheNeighborhoodBefore = 1,
      cacheNeighborhoodAfter = 1,
      deployStreamChunkSize = 1
    )
    val kamonSettings = Configuration.Kamon(
      prometheus = false,
      zipkin = false,
      sigar = false,
      influx = false
    )
    val influx = Configuration.Influx(
      "0.0.0.0",
      1,
      "test",
      "https",
      "user".some,
      "password".some
    )
    val log = Configuration.Log(
      level = izumi.logstage.api.Log.Level.Info,
      jsonPath = Paths.get("/tmp/json.log").some
    )

    Configuration(
      log,
      server,
      grpcServer,
      tls,
      casper,
      blockStorage,
      kamonSettings,
      influx.some
    )
  }

  test("""
        |Configuration.updatePath should
        |respect server.dataDir changes for 'Path' options""".stripMargin) {
    forAll { newDataDir: Path =>
      def updateDataDir(c: Configuration, dataDir: Path): Configuration =
        c.copy(server = c.server.copy(dataDir = dataDir))
      val conf = Configuration.updatePaths(
        updateDataDir(defaultConf, newDataDir),
        defaultConf.server.dataDir
      )
      val paths = gatherPaths(conf)
      val invalidPaths = paths
        .filterNot {
          case (_, path) =>
            path.startsWith(newDataDir)
        }
        .map(_._1)
      assert(invalidPaths.isEmpty, "Invalid paths, wrap them into 'Configuration#adjustPath'")
    }
  }

  test("""
        |Configuration.parse should properly parse
        |TOML config file and environment variables regarding InfluxAuth
    """.stripMargin) {
    forAll { (file: Option[Configuration], env: Option[Configuration]) =>
      val fileContent = file.map(toToml).getOrElse("")
      if (fileContent.nonEmpty) {
        writeTestConfigFile(fileContent)
      }

      val cliArgs =
        if (fileContent.nonEmpty) {
          Array(s"--config-file=$configFilename", "run")
        } else {
          Array("run")
        }

      val envVars = env.map(toEnvVars).getOrElse(Map.empty)

      val expected = {
        val fileOrDefault = file.map(fallback(_, defaultConf)).getOrElse(defaultConf)
        val envOrFile     = env.map(fallback(_, fileOrDefault)).getOrElse(fileOrDefault)
        envOrFile
      }

      val Valid((_, result)) = Configuration.parse(cliArgs, envVars)
      expected.influx shouldEqual result.influx
    }
  }

  test("""
      |Configuration.parse should properly parse
      |CLI options, environment variables and TOML config file
      |ignoring Influx because there is no way providing its auth through CLI
      |""".stripMargin) {
    forAll {
      (
          cli: Option[Configuration],
          file: Option[Configuration],
          env: Option[Configuration]
      ) =>
        val fileContent = file.map(toToml).getOrElse("")
        if (fileContent.nonEmpty) {
          writeTestConfigFile(fileContent)
        }

        val cliArgs = {
          (cli.map(toCli).getOrElse(Nil), fileContent) match {
            case (c, f) if c.nonEmpty && f.nonEmpty =>
              s"--config-file=$configFilename" :: "run" :: c
            case (c, f) if c.nonEmpty && f.isEmpty => "run" :: c
            case (c, f) if c.isEmpty && f.nonEmpty =>
              s"--config-file=$configFilename" :: "diagnostics" :: Nil
            case (c, f) if c.isEmpty && f.isEmpty =>
              "diagnostics" :: Nil
          }
        }.toArray

        val envVars = env.map(toEnvVars).getOrElse(Map.empty)

        val expected = {
          val fileOrDefault = file.map(fallback(_, defaultConf)).getOrElse(defaultConf)
          val envOrFile     = env.map(fallback(_, fileOrDefault)).getOrElse(fileOrDefault)
          val cliOrEnv      = cli.map(fallback(_, envOrFile)).getOrElse(envOrFile)
          Configuration.updatePaths(cliOrEnv, defaultConf.server.dataDir)
        }

        val Valid((_, result)) = Configuration.parse(cliArgs, envVars)

        expected.server shouldEqual result.server
        expected.grpc shouldEqual result.grpc
        expected.tls shouldEqual result.tls
        expected.casper shouldEqual result.casper
        expected.blockstorage shouldEqual result.blockstorage
        expected.metrics shouldEqual result.metrics
    }
  }

  def gatherPaths(c: Configuration): List[(String, Path)] = {
    import magnolia._

    import scala.language.experimental.macros

    trait Filter[A] {
      def filter(fieldNames: List[String], a: A): List[(String, Path)]
    }

    object Filter {
      type Typeclass[T] = Filter[T]

      def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
        (fieldNames, v) =>
          caseClass.parameters.toList
            .flatMap(p => p.typeclass.filter(p.label :: fieldNames, p.dereference(v)))
      def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
        (fieldNames, v) => sealedTrait.dispatch(v)(s => s.typeclass.filter(fieldNames, s.cast(v)))
      implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]
      implicit def default[A: NotPath: NotSubConfig]: Filter[A] =
        (_, _) => Nil
      implicit def option[A](implicit F: Filter[A]): Filter[Option[A]] =
        (fieldNames, opt) => opt.toList.flatMap(v => F.filter(fieldNames, v))
      implicit val pathFilter: Filter[Path] =
        (fieldNames, p) => List((fieldNames.reverse.mkString("."), p))
    }

    Filter.gen[Configuration].filter(Nil, c)
  }

  def fallback(a: Configuration, b: Configuration): Configuration = {
    import magnolia._

    import scala.language.experimental.macros

    trait Merge[A] {
      def merge(a: A, b: A): A
    }

    object Merge {
      type Typeclass[T] = Merge[T]
      def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
        (a, b) => caseClass.construct(p => p.typeclass.merge(p.dereference(a), p.dereference(b)))
      def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] = ???
      implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]

      implicit def default[A: NotSubConfig: NotOption]: Merge[A] =
        (a, _) => a
      implicit def optionSubConfig[A: IsSubConfig](implicit M: Merge[A]): Merge[Option[A]] =
        (maybeA, maybeB) =>
          (maybeA, maybeB) match {
            case (Some(a), Some(b)) => M.merge(a, b).some
            case (Some(a), None)    => a.some
            case (None, Some(b))    => b.some
            case (None, None)       => None
          }
      implicit def optionPlain[A: NotSubConfig: NotOption]: Merge[Option[A]] = _ orElse _
      implicit def listMerge[A]: Merge[List[A]] = {
        case (Nil, b) => b
        case (a, _)   => a
      }
    }

    Merge.gen[Configuration].merge(a, b)
  }

  def toToml(conf: Configuration): String = {
    def dashify(s: String): String =
      s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1-$2")
        .toLowerCase

    val tables = reduce(conf, Map.empty[String, Map[String, String]]) {
      case s: String             => s""""$s""""
      case d: FiniteDuration     => s""""${d.toString.replace(" ", "")}""""
      case p: NodeWithoutChainId => s""""${p.show}""""
      case p: java.nio.file.Path => s""""${p.toString}""""
      case x                     => x.toString
    } {
      case (acc, _, "") =>
        acc
      case (acc, fullFieldName, field) =>
        val tableName :: fieldName = fullFieldName
        val table                  = dashify(tableName)
        val key                    = dashify(fieldName.mkString("-"))
        val previousTable          = acc.getOrElse(table, Map.empty[String, String])
        val updatedKeys            = previousTable + (key -> field)
        acc.updated(table, updatedKeys)
    }

    val sb = new StringBuilder
    tables.foreach {
      case (table, subtable) =>
        sb.append(s"[$table]\n")
        subtable.foreach {
          case (k, v) =>
            sb.append(s"$k = $v\n")
        }
    }
    sb.toString()
  }

  def toCli(conf: Configuration): List[String] =
    toEnvVars(conf)
      .filterKeys(k => k != "CL_INFLUX_USER" && k != "CL_INFLUX_PASSWORD")
      .flatMap {
        case (k, v) =>
          val key = "--" + k.toLowerCase.replace('_', '-').drop(3)
          v match {
            case "true"  => Some(s"$key")
            case "false" => None
            case ""      => None
            case _       => Some(s"$key=$v")
          }
      }
      .toList

  def toEnvVars(conf: Configuration): Map[String, String] = {
    val mapper = (_: String).replace(" ", "")

    reduce(conf, Map.empty[String, String])({
      case n: NodeWithoutChainId => mapper(n.show)
      case x                     => mapper(x.toString)
    }) {
      case (envVars, _, "") =>
        envVars
      case (envVars, fieldName, field) =>
        envVars + (snakify(("CL" :: fieldName).mkString("_")) -> field)
    }
  }

  def reduce[Accumulator](conf: Configuration, accumulator: Accumulator)(
      innerFieldsMapper: Any => String
  )(reducer: (Accumulator, List[String], String) => Accumulator): Accumulator = {
    import magnolia._

    import scala.language.experimental.macros

    trait Flattener[A] {
      def flatten(path: List[String], a: A): List[(List[String], String)]
    }

    object Flattener {
      type Typeclass[T] = Flattener[T]
      def combine[T: NotNode](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
        (path, v) =>
          caseClass.parameters.toList
            .flatMap(
              p =>
                if (p.annotations.exists(_.isInstanceOf[ignore])) {
                  List.empty
                } else {
                  p.typeclass.flatten(path :+ p.label, p.dereference(v))
                }
            )
      def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] = ???
      implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]

      implicit val peerNode: Flattener[NodeWithoutChainId] =
        (path, a) => List((path, innerFieldsMapper(a)))

      implicit val peerNodes: Flattener[List[NodeWithoutChainId]] =
        (path, xs) => List((path, xs.map(innerFieldsMapper).mkString(" ")))

      implicit def default[A: NotSubConfig]: Flattener[A] =
        (path, a) => List((path, innerFieldsMapper(a)))

      implicit def optionSubConfig[A: IsSubConfig](implicit F: Flattener[A]): Flattener[Option[A]] =
        (path, opt) => opt.toList.flatMap(subconfig => F.flatten(path, subconfig))

      implicit def optionPlain[A: NotSubConfig]: Flattener[Option[A]] =
        (path, opt) => opt.toList.map(a => (path, innerFieldsMapper(a)))
    }

    Flattener
      .gen[Configuration]
      .flatten(Nil, conf)
      .foldLeft(accumulator) {
        case (acc, (fieldName, field)) =>
          reducer(acc, fieldName, field)
      }
  }

  override protected def afterEach(): Unit = Files.deleteIfExists(Paths.get(configFilename))

  def writeTestConfigFile(conf: String): Unit = {
    Files.deleteIfExists(Paths.get(configFilename))
    Files.write(
      Paths.get(configFilename),
      conf.getBytes("UTF-8"),
      StandardOpenOption.CREATE_NEW
    )
  }
}
