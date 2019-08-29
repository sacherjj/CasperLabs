package io.casperlabs.shared

import cats.Id
import monix.execution.UncaughtExceptionReporter
import scala.concurrent.duration.FiniteDuration

class UncaughtExceptionHandler(shutdownTimeout: FiniteDuration)
    extends UncaughtExceptionReporter
    with RuntimeOps {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  private val log: Log[Id]                  = Log.logId

  override def reportFailure(ex: scala.Throwable): Unit = {
    log.error(s"Uncaught Exception : ${ex.getMessage}", ex)
    ex match {
      case _: VirtualMachineError | _: LinkageError =>
        // To flush logs
        Thread.sleep(1000)
        exitOrHalt(1, shutdownTimeout)
      case _ =>
    }
  }
}
