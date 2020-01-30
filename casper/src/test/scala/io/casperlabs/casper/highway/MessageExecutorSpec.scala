package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._

class MessageExecutorSpec
    extends FlatSpec
    with Matchers
    with HighwayFixture
    with ArbitraryConsensus {

  def executorFixture(f: SQLiteStorage.CombinedStorage[Task] => ExecutorFixture): Unit =
    withCombinedStorage() { db =>
      f(db).test
    }

  abstract class ExecutorFixture()(implicit db: SQLiteStorage.CombinedStorage[Task])
      extends Fixture(length = Duration.Zero) {
    def test: Task[Unit]

    lazy val executor = new MessageExecutor[Task](
      chainName = chainName,
      genesis = genesisBlock,
      upgrades = Seq.empty,
      maybeValidatorId = none
    )
  }

  behavior of "validateAndAdd"

  it should "" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def test =
        for {
          _ <- insertGenesis()
        } yield ()
    }
  }
}
