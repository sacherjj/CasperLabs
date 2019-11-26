package io.casperlabs.shared

import cats._
import cats.data._
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits._
import Catscontrib._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox
import logstage.{ConsoleSink, IzLogger, LogIO}
import java.nio.file.Path
import izumi.logstage.api.Log._
import izumi.logstage.api.{Log => IzLog}
import izumi.logstage.api.AbstractLogger
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.routing.StaticLogRouter
import izumi.logstage.sink.file.FileSink
import izumi.logstage.sink.file.FileServiceImpl.RealFile
import izumi.logstage.sink.file.FileServiceImpl
import izumi.logstage.sink.file.models.FileRotation
import izumi.logstage.sink.file.models.FileSinkConfig
import izumi.logstage.api.rendering.json.LogstageCirceRenderingPolicy
import izumi.logstage.api.logger.LogSink
import izumi.logstage.api.logger.LogRouter

// Keeping this for now so that maybe we can utilise it with IzLogger as well.
trait LogSource {
  val clazz: Class[_]
}

object LogSource {
  def apply(c: Class[_]): LogSource = new LogSource {
    val clazz: Class[_] = c
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null")) // false-positive
  implicit def matLogSource: LogSource = macro LogSourceMacros.mkLogSource
}

class LogSourceMacros(val c: blackbox.Context) {
  import c.universe._

  def mkLogSource: c.Expr[LogSource] = {
    val tree =
      q"""
          io.casperlabs.shared.LogSource(${c.reifyEnclosingRuntimeClass}.asInstanceOf[Class[_]])
       """

    c.Expr[LogSource](tree)
  }
}

// Mixed into the `shared` package so the existing `Log` aliases keep working.
trait LogPackage {
  type Log[F[_]] = LogIO[F]
}

object Log {
  def apply[F[_]](implicit L: Log[F]): Log[F] = L

  // This should only be used in testing, it disables all logging even SLF4j
  // Otherwise we see output from http4s during tests, something that used
  // to be disabled by having a test version of logback.xml
  // In the future, we could use TypeSafeConfig to set up logstage,
  // but the `logstage-config` module that feature requires doesn't seem
  // to be implemented yet.
  // https://github.com/7mind/izumi/blob/baea35e54dd482e54cd2d075e774cfd26806897a/doc/microsite/src/main/tut/logstage/config.md

  def NOPLog[F[_]: Sync] =
    useLogger[F](IzLogger.NullLogger)

  def log[F[_]: Sync](logger: AbstractLogger): Log[F] =
    LogIO.fromLogger(logger)

  // Originally we used ScalaLogger which uses SLF4J that dynamically
  // gets injected with sinks from the classpath. Some parts of the
  // program get this value as a singleton. We can set it once during
  // startup.
  private var _logId: Log[Id] = log(mkLogger())

  // Mix this into what needs access to the singleton.
  // Normally it should be passed as a constructor argument instead.
  trait LogId {
    implicit def logId: Log[Id] = _logId
  }

  // Set every global routing to this logger.
  def useLogger[F[_]: Sync](logger: IzLogger): Log[F] = {
    // https://izumi.7mind.io/latest/release/doc/logstage/index.html#slf4j-router
    // configure SLF4j to use the same router that `myLogger` uses
    StaticLogRouter.instance.setup(logger.router)
    // Use by anything that accesses the singleton logId
    _logId = log[Id](logger)
    // Create the wrapper that we can pass around.
    log[F](logger)
  }

  /** Configure a logger that writes text to the console and optionally JSON to a file. */
  def mkLogger(
      level: IzLog.Level = defaultLevel,
      levels: Map[String, IzLog.Level] = defaultLevels,
      jsonPath: Option[Path] = None // Empty so we don't start multiple loggers to the same file accidentally.
  ): IzLogger = {
    var sinks: Vector[LogSink] = Vector(defaultSink)
    jsonPath.foreach { path =>
      sinks = sinks :+ new FileSink[RealFile](
        renderingPolicy = new LogstageCirceRenderingPolicy(),
        fileService = new FileServiceImpl(path.toString),
        // NOTE: What SRE wants is to have log rotation happen at the end of the day with timestamped files. For now they'll deal with this in the OS.
        rotation = FileRotation.DisabledRotation,
        config = FileSinkConfig.soft(Int.MaxValue)
      ) {
        override def recoverOnFail(e: String): Unit = println(e)
      }
    }
    IzLogger(level, sinks, levels)
  }

  private def defaultLevel: IzLog.Level =
    IzLog.Level.parse(sys.env.getOrElse("CL_LOG_LEVEL", "INFO"))

  private def defaultSink = ConsoleSink.text(colored = false)

  private def defaultLevels: Map[String, IzLog.Level] = Map(
    "org.http4s"                                          -> IzLog.Level.Warn,
    "io.netty"                                            -> IzLog.Level.Warn,
    "io.grpc"                                             -> IzLog.Level.Error,
    "org.http4s.blaze.channel.nio1.NIO1SocketServerGroup" -> IzLog.Level.Crit
  )

  implicit class LogOps[F[_]: Log, A](fa: F[A]) {

    /** Materializes an error from `F` context (if any), logs it and returns as Left.
      * Otherwise returns Right(result)
      *
      * @param msg Error message. Defaults to empty.
      * @param logSource
      * @param M
      * @tparam E
      * @return Result. Either an error (wrapped in Left) or result (wrapper in Right).
      */
    def attemptAndLog[E <: Throwable](
        msg: String = ""
    )(implicit M: MonadError[F, E]): F[Either[E, A]] =
      M.attempt(fa).flatMap {
        case Left(ex)         => Log[F].error(s"${msg -> "msg" -> null}: $ex").as(Left(ex))
        case right @ Right(_) => M.pure(right)
      }
  }
}
