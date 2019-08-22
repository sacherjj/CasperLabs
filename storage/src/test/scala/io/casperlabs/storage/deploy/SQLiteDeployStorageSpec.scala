package io.casperlabs.storage.deploy

import io.casperlabs.storage.SQLiteFixture
import monix.eval.Task

class SQLiteDeployStorageSpec
    extends DeployStorageSpec
    with SQLiteFixture[(DeployStorageReader[Task], DeployStorageWriter[Task])] {
  override protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit]
  ): Unit = runSQLiteTest[Unit](test.tupled)

  override def db: String = "/tmp/deploy_storage.db"

  override def createTestResource: Task[(DeployStorageReader[Task], DeployStorageWriter[Task])] =
    SQLiteDeployStorage.create[Task].map(s => (s, s))
}
