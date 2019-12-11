package io.casperlabs.shared

import cats.effect.Sync
import izumi.functional.mono.SyncSafe
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.logstage.api.logger.LogSink
import izumi.logstage.api.rendering.logunits.LogFormat
import logstage.{IzLogger, LogIO}
import logstage.UnsafeLogIO.UnsafeLogIOSyncSafeInstance
import izumi.logstage.api.{Log => IzLog}

// https://medium.com/@rtwnk/logstage-zero-cost-structured-logging-in-scala-part-2-practical-example-3ef27e67e7ee
// https://github.com/7mind/izumi/blob/baea35e54dd482e54cd2d075e774cfd26806897a/logstage/logstage-core/src/main/scala/izumi/logstage/api/rendering/StringRenderingPolicy.scala
class LogSinkStub(prefix: String = "", printEnabled: Boolean = false) extends LogSink {

  @volatile var debugs: Vector[String]    = Vector.empty[String]
  @volatile var infos: Vector[String]     = Vector.empty[String]
  @volatile var warns: Vector[String]     = Vector.empty[String]
  @volatile var errors: Vector[String]    = Vector.empty[String]
  @volatile var causes: Vector[Throwable] = Vector.empty[Throwable]

  // To be able to reconstruct the timeline.
  var all: Vector[String] = Vector.empty[String]

  def reset(): Unit = synchronized {
    debugs = Vector.empty[String]
    infos = Vector.empty[String]
    warns = Vector.empty[String]
    errors = Vector.empty[String]
    causes = Vector.empty[Throwable]
    all = Vector.empty[String]
  }

  override def flush(entry: IzLog.Entry): Unit = synchronized {
    val msg = LogFormat.Default.formatMessage(entry, false).message
    all = all :+ msg
    val lvl = entry.context.dynamic.level match {
      case IzLog.Level.Trace => ???
      case IzLog.Level.Debug =>
        debugs = debugs :+ msg
        "DEBUG"
      case IzLog.Level.Info =>
        infos = infos :+ msg
        "INFO"
      case IzLog.Level.Warn =>
        warns = warns :+ msg
        "WARN"
      case IzLog.Level.Error =>
        errors = errors :+ msg
        entry.firstThrowable.foreach { cause =>
          causes = causes :+ cause
        }
        "ERROR"
      case IzLog.Level.Crit => ???
    }
    if (printEnabled) println(s"${lvl.padTo(5, " ").mkString("")} $prefix $msg")
  }
}

trait LogStub {
  protected def sink: LogSinkStub

  def debugs = sink.debugs
  def infos  = sink.infos
  def warns  = sink.warns
  def errors = sink.errors
  def causes = sink.causes
  def all    = sink.all

  def reset() = sink.reset()
}

object LogStub {
  def apply[F[_]: Sync](
      prefix: String = "",
      printEnabled: Boolean = false
  ): LogIO[F] with LogStub = {
    val sink   = new LogSinkStub(prefix, printEnabled)
    val logger = IzLogger(IzLog.Level.Debug, List(sink))
    apply[F](logger, sink)
  }

  // Based on LogIO.fromLogger
  private def apply[F[_]: SyncSafe](logger: IzLogger, sink0: LogSinkStub): LogIO[F] with LogStub =
    new UnsafeLogIOSyncSafeInstance[F](logger) (SyncSafe[F]) with LogIO[F] with LogStub {
      override val sink = sink0

      override def log(entry: IzLog.Entry): F[Unit] =
        F.syncSafe(logger.log(entry))

      override def log(
          logLevel: IzLog.Level
      )(messageThunk: => IzLog.Message)(implicit pos: CodePositionMaterializer): F[Unit] =
        F.syncSafe(logger.log(logLevel)(messageThunk))

      override def withCustomContext(context: IzLog.CustomContext): LogIO[F] =
        LogStub.apply[F](logger.withCustomContext(context), sink)
    }
}
