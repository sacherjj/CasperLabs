package io.casperlabs.storage.deploy

import io.casperlabs.storage.{SQLiteFixture, SQLiteStorage}
import monix.eval.Task

import scala.concurrent.duration._

class SQLiteDeployStorageSpec
    extends DeployStorageSpec
    with SQLiteFixture[(DeployStorageReader[Task], DeployStorageWriter[Task])] {
  override protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds,
      deployBufferChunkSize: Int = 100
  ): Unit = runSQLiteTest[Unit](test.tupled, timeout)

  override def db: String = "/tmp/deploy_storage.db"

  override def numAccounts: Int   = 2
  override def numValidators: Int = 2

  override def createTestResource: Task[(DeployStorageReader[Task], DeployStorageWriter[Task])] =
    SQLiteStorage
      .create[Task]()
      .map(s => (s, s))
}
