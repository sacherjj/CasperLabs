package io.casperlabs.storage.deploy
import io.casperlabs.shared.Log
import io.casperlabs.shared.Log.NOPLog
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock.permit

import scala.concurrent.duration._

class MockDeployStorageSpec extends DeployStorageSpec {
  override protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds
  ): Unit =
    (for {
      implicit0(logNOP: Log[Task]) <- Task(Log.NOPLog[Task])
      mock                         <- MockDeployStorage.create[Task]()
      _                            <- test(mock.reader, mock.writer)
    } yield ()).runSyncUnsafe(timeout)
}
