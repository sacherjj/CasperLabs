package io.casperlabs.node.configuration
import java.nio.file.{Path, Paths}

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.syntax.either._
import cats.syntax.validated._
import com.github.ghik.silencer.silent
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.casper.CasperConf
import io.casperlabs.comm.discovery.NodeUtils.NodeWithoutChainId
import io.casperlabs.comm.transport.Tls
import io.casperlabs.configuration.{relativeToDataDir, SubConfig}
import io.casperlabs.node.configuration.Utils._
import izumi.logstage.api.{Log => IzLog}
import scala.concurrent.duration.FiniteDuration
import scala.io.Source

/**
  * All subconfigs must extend the [[SubConfig]] trait.
  * It's needed for proper hierarchy traversing by Magnolia typeclasses.
  */
final case class Configuration(
    log: Configuration.Log,
    server: Configuration.Server,
    grpc: Configuration.Grpc,
    tls: Tls,
    casper: CasperConf,
    blockstorage: Configuration.BlockStorage,
    metrics: Configuration.Kamon,
    influx: Option[Configuration.Influx]
)

object Configuration extends ParserImplicits {
  case class Log(
      level: IzLog.Level,
      jsonPath: Option[Path]
  ) extends SubConfig

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
      bootstrap: List[NodeWithoutChainId],
      dataDir: Path,
      maxNumOfConnections: Int,
      maxMessageSize: Int,
      eventStreamBufferSize: Int Refined Positive,
      engineParallelism: Int Refined Positive,
      chunkSize: Int Refined Positive,
      relayFactor: Int,
      relaySaturation: Int,
      approvalRelayFactor: Int,
      approvalPollInterval: FiniteDuration,
      alivePeersCacheExpirationPeriod: FiniteDuration,
      syncMaxPossibleDepth: Int Refined Positive,
      syncMinBlockCountToCheckWidth: Int Refined NonNegative,
      syncMaxBondingRate: Double Refined GreaterEqual[W.`0.0`.T],
      syncMaxDepthAncestorsRequest: Int Refined Positive,
      initSyncMaxNodes: Int,
      initSyncMinSuccessful: Int Refined Positive,
      initSyncMemoizeNodes: Boolean,
      initSyncRoundPeriod: FiniteDuration,
      initSyncSkipFailedNodes: Boolean,
      initSyncStep: Int Refined Positive,
      initSyncMaxBlockCount: Int Refined Positive,
      periodicSyncRoundPeriod: FiniteDuration,
      downloadMaxParallelBlocks: Int,
      downloadMaxRetries: Int Refined NonNegative,
      downloadRetryInitialBackoffPeriod: FiniteDuration,
      downloadRetryBackoffFactor: Double Refined GreaterEqual[W.`1.0`.T],
      relayMaxParallelBlocks: Int,
      relayBlockChunkConsumerTimeout: FiniteDuration,
      cleanBlockStorage: Boolean,
      blockUploadRateMaxRequests: Int Refined NonNegative,
      blockUploadRatePeriod: FiniteDuration,
      blockUploadRateMaxThrottled: Int Refined NonNegative
  ) extends SubConfig

  case class BlockStorage(
      cacheMaxSizeBytes: Long,
      cacheNeighborhoodBefore: Int,
      cacheNeighborhoodAfter: Int,
      deployStreamChunkSize: Int
  ) extends SubConfig

  case class Grpc(
      socket: Path,
      portExternal: Int,
      portInternal: Int,
      useTls: Boolean
  ) extends SubConfig

  sealed trait Command extends Product with Serializable
  object Command {
    final case object Run extends Command
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
}
