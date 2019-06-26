package io.casperlabs.shared

import cats.Id
import monix.execution.UncaughtExceptionReporter

object UncaughtExceptionHandler extends UncaughtExceptionReporter {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  private val log: Log[Id]                  = Log.logId

  override def reportFailure(ex: scala.Throwable): Unit = {
    log.error(s"Uncaught Exception : ${ex.getMessage}", ex)
    ex match {
      case _: VirtualMachineError | _: LinkageError =>
        // To flush logs
        Thread.sleep(1000)
        sys.exit(1)
    }
  }
}
