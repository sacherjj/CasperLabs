package io.casperlabs.shared

import cats.Id
import monix.execution.UncaughtExceptionReporter
import scala.concurrent.duration.FiniteDuration

class UncaughtExceptionHandler(shutdownTimeout: FiniteDuration)
    extends UncaughtExceptionReporter
    with RuntimeOps {
  override def reportFailure(ex: scala.Throwable): Unit = {
    Log.logId.error(s"Uncaught Exception : $ex")
    ex match {
      case _: VirtualMachineError | _: LinkageError =>
        // To flush logs
        Thread.sleep(1000)
        exitOrHalt(1, shutdownTimeout)
      case _ =>
    }
  }
}
