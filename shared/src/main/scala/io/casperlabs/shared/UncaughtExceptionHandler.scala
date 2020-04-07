package io.casperlabs.shared

import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import java.util.{Date, SimpleTimeZone, TimeZone}

import cats.Id
import monix.execution.UncaughtExceptionReporter

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class UncaughtExceptionHandler(shutdownTimeout: FiniteDuration)(implicit logId: Log[Id])
    extends UncaughtExceptionReporter
    with RuntimeOps {
  override def reportFailure(ex: scala.Throwable): Unit = {
    Try(Log[Id].error(s"Uncaught Exception : $ex")).recover {
      case ex =>
        // Logstage example formatting "2020-04-07T02:45:56.815 "
        val dateTimeFormatter =
          DateTimeFormatter.ofPattern("YYYY-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.of("UTC"))
        val timestamp = dateTimeFormatter.format(Instant.now())
        println(
          s"ERROR $timestamp (UncaughtExceptionHandler.scala) Exception thrown when logging. " +
            s"Original stacktrace:\n${ex.getMessage}"
        )
        ex.printStackTrace()
    }
    ex match {
      case _: VirtualMachineError | _: LinkageError | _: FatalErrorShutdown =>
        // To flush logs
        Thread.sleep(3000)
        exitOrHalt(1, shutdownTimeout)
      case _ =>
    }
  }
}
