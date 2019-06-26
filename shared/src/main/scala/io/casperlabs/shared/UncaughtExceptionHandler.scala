package io.casperlabs.shared

import cats.Id
import monix.execution.UncaughtExceptionReporter

import scala.util.control.NonFatal

object UncaughtExceptionHandler extends UncaughtExceptionReporter {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  private val log: Log[Id]                  = Log.logId

  override def reportFailure(ex: scala.Throwable): Unit = {
    log.error(s"Uncaught Exception : ${ex.getMessage}", ex)
    if (!NonFatal(ex)) {
      sys.exit(1)
    }
  }
}
