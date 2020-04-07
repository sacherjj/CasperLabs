package io.casperlabs.shared

import java.util.Date

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
        println(
          s"ERROR ${new Date(System.currentTimeMillis())} (UncaughtExceptionHandler.scala) Exception thrown when logging. " +
            s"Original stacktrace:\n"
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
