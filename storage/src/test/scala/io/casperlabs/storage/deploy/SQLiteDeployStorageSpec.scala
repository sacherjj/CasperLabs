package io.casperlabs.storage.deploy

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.shared.Sorting.byteStringOrdering
import io.casperlabs.storage.{SQLiteFixture, SQLiteStorage}
import monix.eval.Task
import org.scalacheck.Gen

import scala.concurrent.duration._

class SQLiteDeployStorageSpec
    extends DeployStorageSpec
    with SQLiteFixture[(DeployStorageReader[Task], DeployStorageWriter[Task])] {

  override protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds
  ): Unit = testFixtureWithView(test, timeout, DeployInfo.View.FULL)

  private def testFixtureWithView(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds,
      deployView: DeployInfo.View
  ): Unit = runSQLiteTest[Unit](createTestResource(deployView), test.tupled, timeout)

  override def db: String = "/tmp/deploy_storage.db"

  override def numAccounts: Int   = 2
  override def numValidators: Int = 2

  override def createTestResource: Task[(DeployStorageReader[Task], DeployStorageWriter[Task])] =
    createTestResource(DeployInfo.View.FULL)

  private def createTestResource(
      deployView: DeployInfo.View
  ): Task[(DeployStorageReader[Task], DeployStorageWriter[Task])] =
    SQLiteStorage
      .create[Task](readXa = xa, writeXa = xa)
      .map(s => (s.reader(deployView), s.writer))

  "SQLiteDeployStorage" should {
    "not fetch the body if it's not asked for" in forAll(
      deploysGen(),
      Gen.oneOf(randomAccounts)
    ) {
      case (deploys, accountKey) =>
        testFixtureWithView(
          deployView = DeployInfo.View.BASIC,
          test = { (reader: DeployStorageReader[Task], writer: DeployStorageWriter[Task]) =>
            val accountDeploysWithoutBody = deploys
              .filter(_.getHeader.accountPublicKey == accountKey.publicKey)
              .sortBy(d => (d.getHeader.timestamp, d.deployHash))
              .reverse
              .map(_.clearBody)

            for {
              _ <- writer.addAsPending(deploys)
              all <- reader.getDeploysByAccount(
                      PublicKey(accountKey.publicKey),
                      limit = Int.MaxValue,
                      lastTimeStamp = Long.MaxValue,
                      lastDeployHash = ByteString.EMPTY
                    )
              _ = all should contain theSameElementsInOrderAs accountDeploysWithoutBody
            } yield ()
          }
        )
    }
  }
}
