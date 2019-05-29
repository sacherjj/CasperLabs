package io.casperlabs.node.configuration
import java.nio.file.{Path, Paths}

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.validated._
import eu.timepit.refined._
import eu.timepit.refined.numeric._
import eu.timepit.refined.api.Refined
import io.casperlabs.blockstorage.{BlockDagFileStorage, LMDBBlockStore}
import io.casperlabs.casper.CasperConf
import io.casperlabs.node.configuration.Utils._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.transport.Tls
import io.casperlabs.configuration.{relativeToDataDir, SubConfig}
import io.casperlabs.shared.StoreType
import shapeless.<:!<
import toml.Toml
import scala.concurrent.duration.FiniteDuration
import scala.io.Source

/**
  * All subconfigs must extend the [[SubConfig]] trait.
  * It's needed for proper hierarchy traversing by Magnolia typeclasses.
  */
final case class Configuration(
    server: Configuration.Server,
    grpc: Configuration.GrpcServer,
    tls: Tls,
    casper: CasperConf,
    lmdb: LMDBBlockStore.Config,
    blockstorage: BlockDagFileStorage.Config,
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
      defaultTimeout: Int,
      bootstrap: Option[Node],
      dataDir: Path,
      storeType: StoreType,
      maxNumOfConnections: Int,
      maxMessageSize: Int,
      chunkSize: Int,
      useGossiping: Boolean,
      relayFactor: Int,
      relaySaturation: Int,
      // approvalRelayFactor: Int,
      // approvalPollInterval: FiniteDuration,
      // syncMaxPossibleDepth: Int, // Refined Positive,
      // syncMinBlockCountToCheckBranchingFactor: Int,
      // syncMaxBranchingFactor: Int,
      // syncMaxDepthAncestorsRequest: Int,
      downloadMaxParallelBlocks: Int,
      cleanBlockStorage: Boolean
  ) extends SubConfig

  case class GrpcServer(
      host: String,
      socket: Path,
      portExternal: Int,
      portInternal: Int
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
      defaultRaw         <- readFile(Source.fromResource("default-configuration.toml"))
      defaults           <- parseToml(defaultRaw)
      options            <- Options.safeCreate(args, defaults)
      command            <- options.parseCommand
      defaultDataDir     <- readDefaultDataDir
      maybeRawConfigFile <- options.readConfigFile
      maybeConfigFile <- maybeRawConfigFile.fold(none[Map[CamelCase, String]].asRight[String])(
                          parseToml(_).map(_.some)
                        )
      envSnakeCase = envVars.flatMap {
        case (k, v) if k.startsWith("CL_") && isSnakeCase(k) => List(SnakeCase(k) -> v)
        case _                                               => Nil
      }
    } yield
      parse(options.fieldByName, envSnakeCase, maybeConfigFile, defaultDataDir, defaults)
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
    import scala.language.experimental.macros
    import magnolia._

    val dataDir = c.server.dataDir

    trait PathUpdater[A] {
      def update(a: A): A
    }

    implicit def default[A: NotPath: NotSubConfig]: PathUpdater[A] =
      identity(_)
    implicit def option[A](implicit U: PathUpdater[A]): PathUpdater[Option[A]] =
      opt => opt.map(U.update)

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
      defaults   <- parseToml(defaultRaw)
      dataDir <- defaults
                  .get(CamelCase("serverDataDir"))
                  .fold("server default data dir must be defined".asLeft[Path])(
                    s => Parser[Path].parse(s)
                  )
    } yield dataDir

  private[configuration] def parseToml(content: String): Either[String, Map[CamelCase, String]] = {

    def flatten(t: Map[String, toml.Value]): Map[String, String] =
      t.toList.flatMap {
        case (key, toml.Value.Str(value))  => List((key, value))
        case (key, toml.Value.Bool(value)) => List((key, value.toString))
        case (key, toml.Value.Real(value)) => List((key, value.toString))
        case (key, toml.Value.Num(value))  => List((key, value.toString))
        case (key, toml.Value.Tbl(values)) => flatten(values).map { case (k, v) => s"$key-$k" -> v }
        case _                             => Nil
      }.toMap

    for {
      tbl          <- Toml.parse(content)
      dashifiedMap = flatten(tbl.values)
    } yield
      dashifiedMap.map {
        case (k, v) => (dashToCamel(k), v)
      }
  }
}
