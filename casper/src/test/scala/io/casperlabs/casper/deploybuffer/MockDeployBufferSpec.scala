package io.casperlabs.casper.deploybuffer
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock.permit
import scala.concurrent.duration._

class MockDeployBufferSpec extends DeployBufferSpec {
  override protected def testFixture(test: DeployBuffer[Task] => Task[Unit]): Unit =
    (for {
      mockDeployBuffer <- MockDeployBuffer.create[Task]()
      _                <- test(mockDeployBuffer)
    } yield ()).runSyncUnsafe(5.seconds)
}
