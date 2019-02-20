package io.casperlabs.node.configuration

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit

import cats.syntax.option._
import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import io.casperlabs.shared.StoreType
import io.casperlabs.node.configuration.ConfigurationSoft._
import org.scalatest._
import shapeless._

import scala.concurrent.duration._
import scala.reflect.{classTag, ClassTag}
import scala.util.Try

class ConfigurationSoftSpec extends FunSuite with Matchers with BeforeAndAfterEach {
  val configFilename = "test-configuration.toml"

  val defaultConf: ConfigurationSoft = {
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
      server.some,
      grpcServer.some,
      tls.some,
      casper.some,
      lmdb.some,
      blockStorage.some,
      kamonSettings.some,
      influx.some,
      influxAuth.some
    )
  }

  test("""
      |ConfigurationSoft.parse should properly parse
      |CLI, ENV and TOML config file""".stripMargin) {
    runTest()
  }

  def runTest(): Unit =
    for {
      cli  <- List(true, false)
      env  <- List(true, false)
      file <- List(true, false)
    } {
      val fileConf            = if (file) Some(update(update(update(defaultConf)))) else None
      val fileContent: String = fileConf.map(toToml).getOrElse("")

      if (fileContent.nonEmpty) {
        writeTestConfigFile(fileContent)
      }

      val cliConf = if (cli) Some(update(defaultConf)) else None
      val cliArgs: Array[String] = {
        (cliConf.map(toCli).getOrElse(Nil), fileContent) match {
          case (c, f) if c.nonEmpty && f.nonEmpty =>
            s"--config-file=$configFilename" :: "run" :: c
          case (c, f) if c.nonEmpty && f.isEmpty => "run" :: c
          case (c, f) if c.isEmpty && f.nonEmpty =>
            s"--config-file=$configFilename" :: "diagnostics" :: Nil
          case (c, f) if c.isEmpty && f.isEmpty =>
            "diagnostics" :: Nil
        }
      }.toArray

      val envConf                      = if (env) Some(update(update(defaultConf))) else None
      val envVars: Map[String, String] = envConf.map(toEnvVars).getOrElse(Map.empty)

      val expected = {
        val fileOrDefault = fileConf.map(_.fallbackTo(defaultConf)).getOrElse(defaultConf)
        val envOrFile     = envConf.map(_.fallbackTo(fileOrDefault)).getOrElse(fileOrDefault)
        val cliOrEnv      = cliConf.map(_.fallbackTo(envOrFile)).getOrElse(envOrFile)
        cliOrEnv
      }

      val Right(result) = ConfigurationSoft.parse(cliArgs, envVars)

      expected shouldEqual result
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
            .map { case x: Option[_] => x.get }
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
        .map {
          case (k, v) =>
            s"$k = $v"
        }
        .mkString("\n")

    s"""
      |${extractTomlTable("server", conf.server.get)}
      |
      |${extractTomlTable("grpc", conf.grpc.get)}
      |
      |${extractTomlTable("tls", conf.tls.get)}
      |
      |${extractTomlTable("casper", conf.casper.get)}
      |
      |${extractTomlTable("lmdb", conf.lmdb.get)}
      |
      |${extractTomlTable("blockstorage", conf.blockstorage.get)}
      |
      |${extractTomlTable("metrics", conf.metrics.get)}
      |
      |${extractTomlTable("influx", conf.influx.get)}
      |
      |${extractTomlTable("influx-auth", conf.influxAuth.get)}
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
        .zip(caseClass.productIterator.toSeq.map { case x: Option[_] => x.get })
        .map {
          case (k, v) =>
            val key   = prefix + snakify(k)
            val value = v.toString.toLowerCase.replace(" ", "")
            key -> value
        }
    List(
      extractKeyValue(serverPrefix, conf.server.get),
      extractKeyValue(grpcPrefix, conf.grpc.get),
      extractKeyValue(tlsPrefix, conf.tls.get),
      extractKeyValue(casperPrefix, conf.casper.get),
      extractKeyValue(lmdbPrefix, conf.lmdb.get),
      extractKeyValue(blockstoragePrefix, conf.blockstorage.get),
      extractKeyValue(metricsPrefix, conf.metrics.get),
      extractKeyValue(influxPrefix, conf.influx.get),
      extractKeyValue(influxAuthPrefix, conf.influxAuth.get)
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
      serverGen.from(serverGen.to(conf.server.get).map(mapper)).some,
      grpcGen.from(grpcGen.to(conf.grpc.get).map(mapper)).some,
      tlsGen.from(tlsGen.to(conf.tls.get).map(mapper)).some,
      casperGen.from(casperGen.to(conf.casper.get).map(mapper)).some,
      lmdbGen.from(lmdbGen.to(conf.lmdb.get).map(mapper)).some,
      blockstorageGen.from(blockstorageGen.to(conf.blockstorage.get).map(mapper)).some,
      metricsGen.from(metricsGen.to(conf.metrics.get).map(mapper)).some,
      influxGen.from(influxGen.to(conf.influx.get).map(mapper)).some,
      conf.influxAuth
    )
  }

  def writeTestConfigFile(conf: String): Unit = {
    Files.deleteIfExists(Paths.get(configFilename))
    Files.write(
      Paths.get(configFilename),
      conf.getBytes("UTF-8"),
      StandardOpenOption.CREATE_NEW
    )
  }
}
