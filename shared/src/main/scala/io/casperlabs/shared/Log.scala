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
import logstage.{IzLogger, LogIO}
import izumi.logstage.api.Log._
import izumi.logstage.api.AbstractLogger
import izumi.fundamentals.platform.language.CodePositionMaterializer

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

// Mixed into the `shared` package so the existing `Log` alieases keep working.
trait LogPackage {
  type Log[F[_]] = LogIO[F]
}

object Log extends LogInstances {
  def apply[F[_]](implicit L: Log[F]): Log[F] = L

  def NOPLog[F[_]: Sync] =
    log[F](IzLogger.NullLogger)
}

sealed abstract class LogInstances {

  def log[F[_]: Sync](logger: AbstractLogger): Log[F] =
    LogIO.fromLogger(logger)

  // Originally we used ScalaLogger which uses SLF4J that dynamically
  // gets injected with sinks from the classpath. Some parts of the
  // program get this value as a singleton. We can set it once during
  // startup.
  var logId: Log[Id] = log(IzLogger())
}
