package io.casperlabs.node.configuration

import java.io.File
import java.nio.file
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit

import cats.syntax.option._
import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import io.casperlabs.node.configuration.ConfigurationSoft._
import io.casperlabs.shared.StoreType
import org.scalacheck.ScalacheckShapeless._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import shapeless._

import scala.concurrent.duration._
import scala.reflect.{classTag, ClassTag}

class ConfigurationSoftSpec
    extends FunSuite
    with Matchers
    with BeforeAndAfterEach
    with GeneratorDrivenPropertyChecks {
  val configFilename: String = s"test-configuration.toml"

  implicit val pathGen: Arbitrary[file.Path] = Arbitrary {
    for {
      n     <- Gen.size
      paths <- Gen.listOfN(n, Gen.alphaNumStr)
    } yield Paths.get(paths.mkString(File.pathSeparator))
  }

  //Needed to pass through CLI options parsing
  implicit val nonEmptyStringGen: Arbitrary[String] = Arbitrary {
    for {
      n   <- Gen.choose(1, 100)
      seq <- Gen.listOfN(n, Gen.alphaNumChar)
    } yield seq.mkString("")
  }

  //There is no way expressing explicit 'false' using CLI options.
  implicit val optionBooleanGen: Arbitrary[Option[Boolean]] = Arbitrary {
    Gen.oneOf(None, Some(true))
  }

  implicit val peerNodeGen: Arbitrary[PeerNode] = Arbitrary {
    for {
      n        <- Gen.choose(1, 100)
      bytes    <- Gen.listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue))
      id       = NodeIdentifier(bytes)
      host     <- Gen.listOfN(n, Gen.alphaNumChar)
      tcpPort  <- Gen.posNum[Int]
      udpPort  <- Gen.posNum[Int]
      endpoint = Endpoint(host.mkString(""), tcpPort, udpPort)
    } yield PeerNode(id, endpoint)
  }

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 500
    )

  implicit val defaultConf: ConfigurationSoft = {
    val server = Server(
      host = "test".some,
      port = 1.some,
      httpPort = 1.some,
      kademliaPort = 1.some,
      dynamicHostAddress = false.some,
      noUpnp = false.some,
      defaultTimeout = 1.some,
      bootstrap = PeerNode(
        NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
        Endpoint("52.119.8.109", 1, 1)
      ).some,
      standalone = false.some,
      mapSize = 1L.some,
      storeType = StoreType.LMDB.some,
      dataDir = Paths.get("test").some,
      maxNumOfConnections = 1.some,
      maxMessageSize = 1.some,
      chunkSize = 1.some
    )
    val grpcServer = GrpcServer(
      host = "test".some,
      socket = Paths.get("test").some,
      portExternal = 1.some,
      portInternal = 1.some
    )
    val casper = Casper(
      validatorPublicKey = "test".some,
      validatorPrivateKey = "test".some,
      validatorPrivateKeyPath = Paths.get("test").some,
      validatorSigAlgorithm = "test".some,
      bondsFile = Paths.get("test").some,
      knownValidatorsFile = "test".some,
      numValidators = 1.some,
      walletsFile = Paths.get("test").some,
      minimumBond = 1L.some,
      maximumBond = 1L.some,
      hasFaucet = false.some,
      requiredSigs = 1.some,
      shardId = "test".some,
      approveGenesis = false.some,
      approveGenesisInterval = FiniteDuration(1, TimeUnit.SECONDS).some,
      approveGenesisDuration = FiniteDuration(1, TimeUnit.SECONDS).some,
      deployTimestamp = 1L.some
    )
    val tls = Tls(
      certificate = Paths.get("test").some,
      key = Paths.get("test").some,
      secureRandomNonBlocking = false.some
    )
    val lmdb = LmdbBlockStore(
      blockStoreSize = 1L.some,
      maxDbs = 1.some,
      maxReaders = 1.some,
      useTls = false.some
    )
    val blockStorage = BlockDagFileStorage(
      latestMessagesLogMaxSizeFactor = 1.some
    )
    val kamonSettings = Metrics(
      false.some,
      false.some,
      false.some
    )
    val influx = Influx(
      "0.0.0.0".some,
      1.some,
      "test".some,
      "https".some
    )
    val influxAuth = InfluxAuth(
      "user".some,
      "password".some
    )
    ConfigurationSoft(
      server,
      grpcServer,
      tls,
      casper,
      lmdb,
      blockStorage,
      kamonSettings,
      influx,
      influxAuth
    )
  }

  test("""
      |ConfigurationSoft.parse should properly parse
      |TOML config file and environment variables regarding InfluxAuth
    """.stripMargin) {
    forAll { (file: Option[ConfigurationSoft], env: Option[ConfigurationSoft]) =>
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
        val fileOrDefault = file.map(_.fallbackTo(defaultConf)).getOrElse(defaultConf)
        val envOrFile     = env.map(_.fallbackTo(fileOrDefault)).getOrElse(fileOrDefault)
        envOrFile
      }

      val Right(result) = ConfigurationSoft.parse(cliArgs, envVars)

      expected.influxAuth shouldEqual result.influxAuth
    }
  }

  test("""
      |ConfigurationSoft.parse should properly parse
      |CLI options, environment variables and TOML config file
      |ignoring InfluxAuth because there is no way providing it through CLI
      |""".stripMargin) {
    forAll {
      (
          cli: Option[ConfigurationSoft],
          file: Option[ConfigurationSoft],
          env: Option[ConfigurationSoft]
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
          val fileOrDefault = file.map(_.fallbackTo(defaultConf)).getOrElse(defaultConf)
          val envOrFile     = env.map(_.fallbackTo(fileOrDefault)).getOrElse(fileOrDefault)
          val cliOrEnv      = cli.map(_.fallbackTo(envOrFile)).getOrElse(envOrFile)
          cliOrEnv
        }

        val Right(result) = ConfigurationSoft.parse(cliArgs, envVars)

        expected.server shouldEqual result.server
        expected.grpc shouldEqual result.grpc
        expected.tls shouldEqual result.tls
        expected.casper shouldEqual result.casper
        expected.lmdb shouldEqual result.lmdb
        expected.blockstorage shouldEqual result.blockstorage
        expected.metrics shouldEqual result.metrics
        expected.influx shouldEqual result.influx
    }
  }

  def toToml(conf: ConfigurationSoft): String = {
    def dashify(s: String): String =
      s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1-$2")
        .toLowerCase

    def extractTomlTable[A <: Product: ClassTag](tableName: String, caseClass: A): String =
      s"[$tableName]" + "\n" + classTag[A].runtimeClass.getDeclaredFields
        .filterNot(_.isSynthetic)
        .map(field => dashify(field.getName))
        .zip(
          caseClass.productIterator.toSeq
            .map {
              case Some(x) => x
              case None    => None
            }
            .map {
              case s: String               => s""""$s""""
              case d: FiniteDuration       => s""""${d.toString.replace(" ", "")}""""
              case _: StoreType.Mixed.type => s""""mixed""""
              case _: StoreType.LMDB.type  => s""""lmdb""""
              case _: StoreType.InMem.type => s""""inmem""""
              case p: PeerNode             => s""""${p.toString}""""
              case p: java.nio.file.Path   => s""""${p.toString}""""
              case x                       => x.toString
            }
        )
        .filterNot(_._2 == "None")
        .map {
          case (k, v) =>
            s"$k = $v"
        }
        .mkString("\n")

    s"""
      |${extractTomlTable("server", conf.server)}
      |
      |${extractTomlTable("grpc", conf.grpc)}
      |
      |${extractTomlTable("tls", conf.tls)}
      |
      |${extractTomlTable("casper", conf.casper)}
      |
      |${extractTomlTable("lmdb", conf.lmdb)}
      |
      |${extractTomlTable("blockstorage", conf.blockstorage)}
      |
      |${extractTomlTable("metrics", conf.metrics)}
      |
      |${extractTomlTable("influx", conf.influx)}
      |
      |${extractTomlTable("influx-auth", conf.influxAuth)}
    """.stripMargin
  }

  def toCli(conf: ConfigurationSoft): List[String] =
    toEnvVars(conf)
      .filterKeys(k => !k.contains("CL_INFLUX_AUTH"))
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

  def toEnvVars(conf: ConfigurationSoft): Map[String, String] = {
    val serverPrefix       = "CL_SERVER_"
    val grpcPrefix         = "CL_GRPC_"
    val tlsPrefix          = "CL_TLS_"
    val casperPrefix       = "CL_CASPER_"
    val lmdbPrefix         = "CL_LMDB_"
    val blockstoragePrefix = "CL_BLOCKSTORAGE_"
    val metricsPrefix      = "CL_METRICS_"
    val influxPrefix       = "CL_INFLUX_"
    val influxAuthPrefix   = "CL_INFLUX_AUTH_"

    def snakify(s: String): String =
      s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
        .toUpperCase

    def extractKeyValue[A <: Product: ClassTag](prefix: String, caseClass: A) =
      classTag[A].runtimeClass.getDeclaredFields
        .filterNot(_.isSynthetic)
        .map(_.getName)
        .zip(
          caseClass.productIterator.toSeq
            .map {
              case Some(x) => x
              case None    => None
            }
        )
        .map {
          case (k, v) =>
            val key = prefix + snakify(k)
            key -> {
              v.toString.replace(" ", "") match {
                case x @ ("InMem" | "Mixed" | "LMDB") => x.toLowerCase
                case x                                => x
              }
            }
        }
        .filterNot(_._2 == "None")
    List(
      extractKeyValue(serverPrefix, conf.server),
      extractKeyValue(grpcPrefix, conf.grpc),
      extractKeyValue(tlsPrefix, conf.tls),
      extractKeyValue(casperPrefix, conf.casper),
      extractKeyValue(lmdbPrefix, conf.lmdb),
      extractKeyValue(blockstoragePrefix, conf.blockstorage),
      extractKeyValue(metricsPrefix, conf.metrics),
      extractKeyValue(influxPrefix, conf.influx),
      extractKeyValue(influxAuthPrefix, conf.influxAuth)
    ).flatten.toMap
  }

  def update(conf: ConfigurationSoft): ConfigurationSoft = {
    val serverGen       = Generic[ConfigurationSoft.Server]
    val casperGen       = Generic[ConfigurationSoft.Casper]
    val grpcGen         = Generic[ConfigurationSoft.GrpcServer]
    val tlsGen          = Generic[ConfigurationSoft.Tls]
    val lmdbGen         = Generic[ConfigurationSoft.LmdbBlockStore]
    val blockstorageGen = Generic[ConfigurationSoft.BlockDagFileStorage]
    val metricsGen      = Generic[ConfigurationSoft.Metrics]
    val influxGen       = Generic[ConfigurationSoft.Influx]

    object mapper extends Poly1 {
      implicit def caseInt: mapper.Case.Aux[Option[Int], Option[Int]] =
        at[Option[Int]](_.map(_ + 1))
      implicit def caseLong: mapper.Case.Aux[Option[Long], Option[Long]] =
        at[Option[Long]](_.map(_ + 1))
      implicit def caseString: mapper.Case.Aux[Option[String], Option[String]] =
        at[Option[String]](_.map(_ + "-"))
      implicit def caseFiniteDuration
        : mapper.Case.Aux[Option[FiniteDuration], Option[FiniteDuration]] =
        at[Option[FiniteDuration]](_.map(d => d.plus(1.second)))
      implicit def caseBoolean: mapper.Case.Aux[Option[Boolean], Option[Boolean]] =
        at[Option[Boolean]](_.map(b => !b))
      implicit def casePath
        : mapper.Case.Aux[Option[java.nio.file.Path], Option[java.nio.file.Path]] =
        at[Option[java.nio.file.Path]](_.map(p => java.nio.file.Paths.get(p.toString + "-")))
      implicit def casePeerNode: mapper.Case.Aux[Option[PeerNode], Option[PeerNode]] =
        at[Option[PeerNode]](
          _.map(
            p =>
              p.copy(
                endpoint = p.endpoint
                  .copy(tcpPort = p.endpoint.tcpPort + 1, udpPort = p.endpoint.udpPort + 1)
            )
          )
        )
      implicit def caseStoreType: mapper.Case.Aux[Option[StoreType], Option[StoreType]] =
        at[Option[StoreType]](_.map {
          case StoreType.Mixed => StoreType.LMDB
          case StoreType.LMDB  => StoreType.InMem
          case StoreType.InMem => StoreType.Mixed
        })
    }

    ConfigurationSoft(
      serverGen.from(serverGen.to(conf.server).map(mapper)),
      grpcGen.from(grpcGen.to(conf.grpc).map(mapper)),
      tlsGen.from(tlsGen.to(conf.tls).map(mapper)),
      casperGen.from(casperGen.to(conf.casper).map(mapper)),
      lmdbGen.from(lmdbGen.to(conf.lmdb).map(mapper)),
      blockstorageGen.from(blockstorageGen.to(conf.blockstorage).map(mapper)),
      metricsGen.from(metricsGen.to(conf.metrics).map(mapper)),
      influxGen.from(influxGen.to(conf.influx).map(mapper)),
      conf.influxAuth
    )
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
