package io.casperlabs.shared

import java.util.{Timer, TimerTask}
import scala.concurrent.duration.FiniteDuration

trait RuntimeOps {

  /** Try exiting normally, but if it doesn't work use the more forceful option.  */
  def exitOrHalt(status: Int, timeout: FiniteDuration): Unit =
    try {
      val timer = new Timer()
      timer.schedule(new TimerTask() {
        override def run(): Unit =
          Runtime.getRuntime.halt(status)
      }, timeout.toMillis)
      System.exit(status)
    } catch {
      case _: Throwable =>
        Runtime.getRuntime.halt(status)
    }
}
