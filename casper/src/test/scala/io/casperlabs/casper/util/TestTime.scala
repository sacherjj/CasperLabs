package io.casperlabs.casper.util

import scala.concurrent.duration._

import io.casperlabs.shared.Time

import monix.eval.Task

object TestTime {
  val instance = new Time[Task] {
    private val timer                               = Task.timer
    def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
    def nanoTime: Task[Long]                        = timer.clock.monotonic(MILLISECONDS)
    def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
  }
}
