package io.casperlabs.node.configuration
import java.nio.file.{Path, Paths}

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.either._
import cats.syntax.validated._
import com.github.ghik.silencer.silent
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.blockstorage.LMDBBlockStorage
import io.casperlabs.casper.CasperConf
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.transport.Tls
import io.casperlabs.configuration.{relativeToDataDir, SubConfig}
import io.casperlabs.node.configuration.Utils._

import scala.concurrent.duration.FiniteDuration
import scala.io.Source

/**
  * All subconfigs must extend the [[SubConfig]] trait.
  * It's needed for proper hierarchy traversing by Magnolia typeclasses.
  */
final case class Configuration(
    server: Configuration.Server,
    grpc: Configuration.Grpc,
    tls: Tls,
    casper: CasperConf,
    lmdb: LMDBBlockStorage.Config,
    blockstorage: Configuration.BlockStorage,
    metrics: Configuration.Kamon,
    influx: Option[Configuration.Influx]
)

object Configuration extends ParserImplicits {
  case class Kamon(
      prometheus: Boolean,
      zipkin: Boolean,
      sigar: Boolean,
      influx: Boolean
  ) extends SubConfig

  case class Influx(
      hostname: String,
      port: Int,
      database: String,
      protocol: String,
      user: Option[String],
      password: Option[String]
  ) extends SubConfig

  case class Server(
      host: Option[String],
      port: Int,
      httpPort: Int,
      kademliaPort: Int,
      dynamicHostAddress: Boolean,
      noUpnp: Boolean,
      defaultTimeout: FiniteDuration,
      bootstrap: Option[Node],
      dataDir: Path,
      maxNumOfConnections: Int,
      maxMessageSize: Int,
      chunkSize: Int,
      useGossiping: Boolean,
      relayFactor: Int,
      relaySaturation: Int,
      approvalRelayFactor: Int,
      approvalPollInterval: FiniteDuration,
      syncMaxPossibleDepth: Int Refined Positive,
      syncMinBlockCountToCheckWidth: Int Refined NonNegative,
      syncMaxBondingRate: Double Refined GreaterEqual[W.`0.0`.T],
      syncMaxDepthAncestorsRequest: Int Refined Positive,
      initSyncMaxNodes: Int,
      initSyncMinSuccessful: Int Refined Positive,
      initSyncMemoizeNodes: Boolean,
      initSyncSkipFailedNodes: Boolean,
      initSyncRoundPeriod: FiniteDuration,
      initSyncMaxBlockCount: Int Refined Positive,
      downloadMaxParallelBlocks: Int,
      downloadMaxRetries: Int Refined NonNegative,
      downloadRetryInitialBackoffPeriod: FiniteDuration,
      downloadRetryBackoffFactor: Double Refined GreaterEqual[W.`1.0`.T],
      relayMaxParallelBlocks: Int,
      relayBlockChunkConsumerTimeout: FiniteDuration,
      cleanBlockStorage: Boolean
  ) extends SubConfig

  case class BlockStorage(
      latestMessagesLogMaxSizeFactor: Int,
      cacheMaxSizeBytes: Long
  ) extends SubConfig

  case class Grpc(
      socket: Path,
      portExternal: Int,
      portInternal: Int,
      useTls: Boolean
  ) extends SubConfig

  sealed trait Command extends Product with Serializable
  object Command {
    final case object Diagnostics extends Command
    final case object Run         extends Command
  }

  def parse(
      args: Array[String],
      envVars: Map[String, String]
  ): ValidatedNel[String, (Command, Configuration)] = {
    val res = for {
      // NOTE: Add default values to node/src/test/resources/default-configuration.toml as well as the main one.
      defaultRaw      <- readFile(Source.fromResource("default-configuration.toml"))
      defaults        = parseToml(defaultRaw)
      options         <- Options.safeCreate(args, defaults)
      command         <- options.parseCommand
      defaultDataDir  <- readDefaultDataDir
      maybeConfigFile <- options.readConfigFile.map(_.map(parseToml))
      envSnakeCase = envVars.flatMap {
        case (k, v) if k.startsWith("CL_") && isSnakeCase(k) => List(SnakeCase(k) -> v)
        case _                                               => Nil
      }
    } yield parse(options.fieldByName, envSnakeCase, maybeConfigFile, defaultDataDir, defaults)
      .map(conf => (command, conf))
    res.fold(_.invalidNel[(Command, Configuration)], identity)
  }

  private def parse(
      cliByName: CamelCase => Option[String],
      envVars: Map[SnakeCase, String],
      configFile: Option[Map[CamelCase, String]],
      defaultDataDir: Path,
      defaultConfigFile: Map[CamelCase, String]
  ): ValidatedNel[String, Configuration] =
    ConfParser
      .gen[Configuration]
      .parse(cliByName, envVars, configFile, defaultConfigFile, Nil)
      .map(updatePaths(_, defaultDataDir))
      .toEither
      .flatMap(updateTls(_, defaultConfigFile).leftMap(NonEmptyList(_, Nil)))
      .fold(Invalid(_), Valid(_))

  /**
    * Updates Configuration 'Path' fields:
    * If a field has [[relativeToDataDir]] annotation, then resolves it against server.dataDir
    * Otherwise replaces a parent of a field to updated server.dataDir
    */
  private[configuration] def updatePaths(c: Configuration, defaultDataDir: Path): Configuration = {
    import magnolia._

    import scala.language.experimental.macros

    val dataDir = c.server.dataDir

    trait PathUpdater[A] {
      def update(a: A): A
    }

    @silent("is never used")
    implicit def default[A: NotPath: NotSubConfig]: PathUpdater[A] =
      identity(_)

    @silent("is never used")
    implicit def option[A](implicit U: PathUpdater[A]): PathUpdater[Option[A]] =
      opt => opt.map(U.update)

    @silent("is never used")
    implicit val pathUpdater: PathUpdater[Path] = (path: Path) =>
      Paths.get(replacePrefix(path, defaultDataDir, dataDir))

    object GenericPathUpdater {
      type Typeclass[T] = PathUpdater[T]

      def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] =
        t =>
          caseClass.construct { p =>
            val relativePath = p.annotations
              .find(_.isInstanceOf[relativeToDataDir])
              .map(_.asInstanceOf[relativeToDataDir])

            relativePath.fold(p.typeclass.update(p.dereference(t)))(
              ann => dataDir.resolve(ann.relativePath).asInstanceOf[p.PType]
            )
          }

      def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] =
        t => sealedTrait.dispatch(t)(s => s.typeclass.update(s.cast(t)))

      implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]
    }
    GenericPathUpdater.gen[Configuration].update(c)
  }

  private[configuration] def updateTls(
      c: Configuration,
      defaultConfigFile: Map[CamelCase, String]
  ): Either[String, Configuration] = {
    val dataDir = c.server.dataDir
    for {
      defaultDataDir <- readDefaultDataDir
      defaultCertificate <- defaultConfigFile
                             .get(CamelCase("tlsCertificate"))
                             .fold("tls.certificate must have default value".asLeft[Path])(
                               s => Parser[Path].parse(s)
                             )
      defaultKey <- defaultConfigFile
                     .get(CamelCase("tlsKey"))
                     .fold("tls.key must have default value".asLeft[Path])(
                       s => Parser[Path].parse(s)
                     )
    } yield {
      val isCertCustomLocation = stripPrefix(c.tls.certificate, dataDir) != stripPrefix(
        defaultCertificate,
        defaultDataDir
      )
      val isKeyCustomLocation =
        stripPrefix(c.tls.key, dataDir) !=
          stripPrefix(defaultKey, defaultDataDir)
      c.copy(
        tls = c.tls.copy(
          customCertificateLocation = isCertCustomLocation,
          customKeyLocation = isKeyCustomLocation
        )
      )
    }
  }

  private def readDefaultDataDir: Either[String, Path] =
    for {
      defaultRaw <- readFile(Source.fromResource("default-configuration.toml"))
      defaults   = parseToml(defaultRaw)
      dataDir <- defaults
                  .get(CamelCase("serverDataDir"))
                  .fold("server default data dir must be defined".asLeft[Path])(
                    s => Parser[Path].parse(s)
                  )
    } yield dataDir

  private[configuration] def parseToml(content: String): Map[CamelCase, String] = {
    val tableRegex = """\[(.+)\]""".r
    val valueRegex = """([a-z\-]+)\s*=\s*\"?([^\"]*)\"?""".r

    val lines = content
      .split('\n')
    val withoutCommentsAndEmptyLines = lines
      .filterNot(s => s.startsWith("#") || s.trim.isEmpty)
      .map(_.trim)

    val dashifiedMap: Map[String, String] = withoutCommentsAndEmptyLines
      .foldLeft((Map.empty[String, String], Option.empty[String])) {
        case ((acc, _), tableRegex(table)) =>
          (acc, Some(table))
        case ((acc, t @ Some(currentTable)), valueRegex(key, value)) =>
          (acc + (currentTable + "-" + key -> value), t)
        case (x, _) => x
      }
      ._1

    val camelCasedMap: Map[CamelCase, String] = dashifiedMap.map {
      case (k, v) => (dashToCamel(k), v)
    }

    camelCasedMap
  }
}
