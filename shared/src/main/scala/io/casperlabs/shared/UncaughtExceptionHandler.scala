package io.casperlabs.shared

import cats.Id
import monix.execution.UncaughtExceptionReporter
import scala.concurrent.duration.FiniteDuration

class UncaughtExceptionHandler(shutdownTimeout: FiniteDuration)(implicit logId: Log[Id])
    extends UncaughtExceptionReporter
    with RuntimeOps {
  override def reportFailure(ex: scala.Throwable): Unit = {
    Log[Id].error(s"Uncaught Exception : $ex")
    ex match {
      case _: VirtualMachineError | _: LinkageError | _: FatalErrorShutdown =>
        // To flush logs
        Thread.sleep(1000)
        exitOrHalt(1, shutdownTimeout)
      case _ =>
    }
  }
}
