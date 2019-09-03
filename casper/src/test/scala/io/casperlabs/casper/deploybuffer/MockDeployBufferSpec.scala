package io.casperlabs.casper.deploybuffer
import io.casperlabs.shared.Log
import io.casperlabs.shared.Log.NOPLog
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock.permit

import scala.concurrent.duration._

class MockDeployBufferSpec extends DeployBufferSpec {
  override protected def testFixture(
      test: DeployBuffer[Task] => Task[Unit],
      timeout: FiniteDuration = 5.seconds,
      deployBufferChunkSize: Int = 100
  ): Unit =
    (for {
      implicit0(logNOP: Log[Task]) <- Task(new NOPLog[Task])
      mockDeployBuffer             <- MockDeployBuffer.create[Task]
      _                            <- test(mockDeployBuffer)
    } yield ()).runSyncUnsafe(timeout)
}
