package io.casperlabs.node.configuration

import java.io.File
import java.nio.file
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit

import cats.syntax.option._
import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import io.casperlabs.node.configuration.ConfigurationSoft._
import io.casperlabs.shared.StoreType
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import io.casperlabs.node.configuration.MagnoliaArbitrary._
import shapeless._
import shapeless.labelled.FieldType
import shapeless.ops.hlist._

import scala.concurrent.duration._

class ConfigurationSoftSpec
    extends FunSuite
    with Matchers
    with BeforeAndAfterEach
    with GeneratorDrivenPropertyChecks {
  type InnerFieldName = String
  type UpperFieldName = String

  val configFilename: String = s"test-configuration.toml"

  //Needed because Scalacheck-shapeless derivation is broken
  implicit val finiteDurationGen: Arbitrary[FiniteDuration] = Arbitrary {
    Gen.posNum[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
  }

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

    val stringBuilder = reduce(conf, new StringBuilder) {
      case s: String               => s""""$s""""
      case d: FiniteDuration       => s""""${d.toString.replace(" ", "")}""""
      case _: StoreType.Mixed.type => s""""mixed""""
      case _: StoreType.LMDB.type  => s""""lmdb""""
      case _: StoreType.InMem.type => s""""inmem""""
      case p: PeerNode             => s""""${p.toString}""""
      case p: java.nio.file.Path   => s""""${p.toString}""""
      case x                       => x.toString
    } { (sb, upperFieldName, fields) =>
      sb.append(s"[${dashify(upperFieldName)}]\n")
      fields.foreach {
        case (k, v) =>
          sb.append(s"${dashify(k)} = $v\n")
      }
      sb.append("\n")
    }
    stringBuilder.toString()
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
    def snakify(s: String): String =
      s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
        .toUpperCase

    reduce(conf, Map.empty[String, String])(_.toString.replace(" ", "") match {
      case x @ ("InMem" | "Mixed" | "LMDB") => x.toLowerCase
      case x                                => x
    }) { (envVars, upperFieldName, innerFields) =>
      envVars ++ innerFields.map {
        case (k, v) =>
          s"CL_${snakify(upperFieldName)}_${snakify(k)}" -> v
      }
    }
  }

  def reduce[Accumulator](conf: ConfigurationSoft, accumulator: Accumulator)(
      innerFieldsMapper: Any => String
  )(
      reducer: (Accumulator, UpperFieldName, List[(InnerFieldName, String)]) => Accumulator
  ): Accumulator = {

    object toKeyValueMapper extends Poly1 {
      implicit def caseAll[K <: Symbol, A](
          implicit w: Witness.Aux[K]
      ) =
        at[FieldType[K, Option[A]]] { maybeField =>
          val value: Option[String] = maybeField.map(v => innerFieldsMapper(v))
          value.map { v =>
            val k = w.value.name
            (k, v)
          }
        }
    }

    def mapToKeyValues[A <: Product, In <: HList, Out <: HList](
        a: A,
        gen: LabelledGeneric.Aux[A, In]
    )(
        implicit
        m: Mapper.Aux[toKeyValueMapper.type, In, Out],
        t: ToTraversable.Aux[Out, List, Option[(String, String)]]
    ): List[(String, String)] =
      gen.to(a).map(toKeyValueMapper).toList.flatten

    object toEnvVarsReducer extends Poly2 {
      implicit def caseGen[K <: Symbol, A <: Product, Repr1 <: HList, Repr2 <: HList](
          implicit g: LabelledGeneric.Aux[A, Repr1],
          w: Witness.Aux[K],
          m: Mapper.Aux[toKeyValueMapper.type, Repr1, Repr2],
          t: ToTraversable.Aux[Repr2, List, Option[(String, String)]]
      ) =
        at[Accumulator, FieldType[K, A]] { (accumulator, product) =>
          val name: String                   = w.value.name
          val fields: List[(String, String)] = mapToKeyValues(product, g)
          reducer(accumulator, name, fields)
        }
    }

    def reduce[A <: Product, Repr <: HList](a: A)(
        implicit g: LabelledGeneric.Aux[A, Repr],
        f: LeftFolder.Aux[Repr, Accumulator, toEnvVarsReducer.type, Accumulator]
    ): Accumulator = g.to(a).foldLeft(accumulator)(toEnvVarsReducer)

    /*_*/
    reduce(conf)
    /*_*/
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
