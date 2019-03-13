package io.casperlabs.node.configuration

import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit

import cats.data.Validated.Valid
import cats.syntax.option._
import io.casperlabs.blockstorage.{BlockDagFileStorage, LMDBBlockStore}
import io.casperlabs.casper.CasperConf
import io.casperlabs.comm.transport.Tls
import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import io.casperlabs.configuration.{ignore, SubConfig}
import io.casperlabs.node.configuration.MagnoliaArbitrary._
import io.casperlabs.node.configuration.Utils._
import io.casperlabs.shared.StoreType
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import shapeless.<:!<

import scala.concurrent.duration._
import scala.io.Source

class ConfigurationSpec
    extends FunSuite
    with Matchers
    with BeforeAndAfterEach
    with GeneratorDrivenPropertyChecks
    with ArbitraryImplicits
    with ParserImplicits {

  val configFilename: String = s"test-configuration.toml"

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 2000,
      workers = 1
    )

  val defaultConf: Configuration = {
    val server = Configuration.Server(
      host = "test".some,
      port = 1,
      httpPort = 1,
      kademliaPort = 1,
      dynamicHostAddress = false,
      noUpnp = false,
      defaultTimeout = 1,
      bootstrap = PeerNode(
        NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
        Endpoint("52.119.8.109", 1, 1)
      ),
      storeType = StoreType.LMDB,
      dataDir = Paths.get("/tmp"),
      maxNumOfConnections = 1,
      maxMessageSize = 1,
      chunkSize = 1,
      relayFactor = 1,
      relaySaturation = 1
    )
    val grpcServer = Configuration.GrpcServer(
      host = "test",
      socket = Paths.get("/tmp/test"),
      portExternal = 1,
      portInternal = 1
    )
    val casper = CasperConf(
      validatorPublicKey = "test".some,
      validatorPrivateKey = "test".some,
      validatorPrivateKeyPath = Paths.get("/tmp/test").some,
      validatorSigAlgorithm = "test",
      bondsFile = Paths.get("/tmp/test"),
      knownValidatorsFile = Paths.get("/tmp/test").some,
      numValidators = 1,
      walletsFile = Paths.get("/tmp/test"),
      minimumBond = 1L,
      maximumBond = 1L,
      hasFaucet = false,
      requiredSigs = 1,
      shardId = "test",
      standalone = false,
      approveGenesis = false,
      approveGenesisInterval = FiniteDuration(1, TimeUnit.SECONDS),
      approveGenesisDuration = FiniteDuration(1, TimeUnit.SECONDS),
      deployTimestamp = 1L.some,
      genesisPath = Paths.get("/tmp/genesis")
    )
    val tls = Tls(
      certificate = Paths.get("/tmp/test"),
      key = Paths.get("/tmp/test"),
      secureRandomNonBlocking = false
    )
    val lmdb = LMDBBlockStore.Config(
      dir = Paths.get("/tmp/lmdb-block-store"),
      blockStoreSize = 1L,
      maxDbs = 1,
      maxReaders = 1,
      useTls = false
    )
    val blockStorage = BlockDagFileStorage.Config(
      latestMessagesLogMaxSizeFactor = 1,
      dir = Paths.get("/tmp/block-dag-file-storage")
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

    Configuration(
      server,
      grpcServer,
      tls,
      casper,
      lmdb,
      blockStorage,
      kamonSettings,
      influx.some
    )
  }

  test("""
        |Configuration.updateTls should update
        |'customCertificateLocation' and 'customKeyLocation'
        |if certificate and key are custom""".stripMargin) {
    forAll { (maybeDataDir: Option[Path], maybeCert: Option[Path], maybeKey: Option[Path]) =>
      import cats.instances.either._
      import cats.syntax.flatMap._
      import shapeless._

      /*_*/
      val confUpdatedDataDir =
        maybeDataDir.fold(defaultConf)(lens[Configuration].server.dataDir.set(defaultConf))
      val confUpdatedCert =
        maybeCert.fold(confUpdatedDataDir)(
          lens[Configuration].tls.certificate.set(confUpdatedDataDir)
        )
      val confUpdatedKey =
        maybeKey.fold(confUpdatedCert)(lens[Configuration].tls.key.set(confUpdatedCert))
      /*_*/

      val Right(defaults) = readFile(Source.fromResource("default-configuration.toml")) >>= Configuration.parseToml
      val Right(res) = Configuration
        .updateTls(Configuration.updatePaths(confUpdatedKey, defaultConf.server.dataDir), defaults)
      val Right(defaultCert) =
        Parser[java.nio.file.Path].parse(defaults(CamelCase("tlsCertificate")))
      val Right(defaultKey) = Parser[java.nio.file.Path].parse(defaults(CamelCase("tlsKey")))

      maybeCert match {
        case Some(c) =>
          val certStrippedPath        = stripPrefix(c, res.server.dataDir)
          val defaultCertStrippedPath = stripPrefix(defaultCert, defaultConf.server.dataDir)
          assert(res.tls.customCertificateLocation && certStrippedPath != defaultCertStrippedPath)
        case None =>
          assert(!res.tls.customCertificateLocation)
      }
      maybeKey match {
        case Some(k) =>
          val keyStrippedPath        = stripPrefix(k, res.server.dataDir)
          val defaultKeyStrippedPath = stripPrefix(defaultKey, defaultConf.server.dataDir)
          assert(res.tls.customKeyLocation && keyStrippedPath != defaultKeyStrippedPath)
        case None => assert(!res.tls.customKeyLocation)
      }
    }
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
        expected.lmdb shouldEqual result.lmdb
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
    }

    Merge.gen[Configuration].merge(a, b)
  }

  def toToml(conf: Configuration): String = {
    def dashify(s: String): String =
      s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1-$2")
        .toLowerCase

    val tables = reduce(conf, Map.empty[String, Map[String, String]]) {
      case s: String               => s""""$s""""
      case d: FiniteDuration       => s""""${d.toString.replace(" ", "")}""""
      case _: StoreType.Mixed.type => s""""mixed""""
      case _: StoreType.LMDB.type  => s""""lmdb""""
      case _: StoreType.InMem.type => s""""inmem""""
      case p: PeerNode             => s""""${p.toString}""""
      case p: java.nio.file.Path   => s""""${p.toString}""""
      case x                       => x.toString
    } { (acc, fullFieldName, field) =>
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
            case _       => Some(s"$key=$v")
          }
      }
      .toList

  def toEnvVars(conf: Configuration): Map[String, String] =
    reduce(conf, Map.empty[String, String])(_.toString.replace(" ", "") match {
      case x @ ("InMem" | "Mixed" | "LMDB") => x.toLowerCase
      case x                                => x
    }) { (envVars, fieldName, field) =>
      envVars + (snakify(("CL" :: fieldName).mkString("_")) -> field)
    }

  def reduce[Accumulator](conf: Configuration, accumulator: Accumulator)(
      innerFieldsMapper: Any => String
  )(reducer: (Accumulator, List[String], String) => Accumulator): Accumulator = {
    import magnolia._

    import scala.language.experimental.macros

    type NotPeerNode[A] = A <:!< PeerNode

    trait Flattener[A] {
      def flatten(path: List[String], a: A): List[(List[String], String)]
    }

    object Flattener {
      type Typeclass[T] = Flattener[T]
      def combine[T: NotPeerNode](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
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

      implicit val peerNode: Flattener[PeerNode] =
        (path, a) => List((path, innerFieldsMapper(a)))
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
