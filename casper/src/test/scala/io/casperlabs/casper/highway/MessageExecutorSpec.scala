package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.concurrent.Semaphore
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.casper.mocks.MockValidation
import io.casperlabs.casper.validation.ValidationImpl
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._

class MessageExecutorSpec extends FlatSpec with Matchers with HighwayFixture {

  def executorFixture(f: SQLiteStorage.CombinedStorage[Task] => ExecutorFixture): Unit =
    withCombinedStorage() { db =>
      f(db).test
    }

  abstract class ExecutorFixture()(implicit db: SQLiteStorage.CombinedStorage[Task])
      extends Fixture(length = Duration.Zero) {
    def test: Task[Unit]

    implicit val consensusConfig = ConsensusConfig()

    override def messageExecutor = new MessageExecutor[Task](
      chainName = chainName,
      genesis = genesisBlock,
      upgrades = Seq.empty,
      maybeValidatorId = none
    )

    def validateAndAdd(block: Block): Task[Unit] =
      for {
        semaphore <- Semaphore[Task](1)
        _         <- messageExecutor.validateAndAdd(semaphore, block, isBookingBlock = false)
      } yield ()
  }

  behavior of "validateAndAdd"

  it should "raise an error if there's a problem with the block" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def validation = new ValidationImpl[Task]()

      override def test =
        for {
          _      <- insertGenesis()
          block  = sample[Block]
          result <- validateAndAdd(block).attempt
        } yield {
          result match {
            case Left(ex)  => ex shouldBe a[ValidateErrorWrapper]
            case Right(()) => fail("Should have raised.")
          }
        }
    }
  }
}
