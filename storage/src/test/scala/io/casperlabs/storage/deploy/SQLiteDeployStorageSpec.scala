package io.casperlabs.storage.deploy

import io.casperlabs.storage.{SQLiteFixture, SQLiteStorage}
import monix.eval.Task

import scala.concurrent.duration._

class SQLiteDeployStorageSpec
    extends DeployStorageSpec
    with SQLiteFixture[(DeployStorageReader[Task], DeployStorageWriter[Task])] {
  override protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds
  ): Unit = runSQLiteTest[Unit](test.tupled, timeout)

  override def db: String = "/tmp/deploy_storage.db"

  override def createTestResource: Task[(DeployStorageReader[Task], DeployStorageWriter[Task])] =
    SQLiteStorage.create[Task](Task.pure).map(s => (s, s))
}
