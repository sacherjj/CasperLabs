package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.concurrent.{Ref, Semaphore}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.casper.mocks.MockValidation
import io.casperlabs.casper.validation.ValidationImpl
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper
import io.casperlabs.casper.finality.MultiParentFinalizer
import io.casperlabs.casper.highway.mocks.MockEventEmitter
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.models.Message
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.shared.Log
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._

class MessageExecutorSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  implicit val consensusConfig = ConsensusConfig()

  def executorFixture(f: SQLiteStorage.CombinedStorage[Task] => ExecutorFixture): Unit =
    withCombinedStorage() { db =>
      f(db).test
    }

  /** Fixture that creates a  */
  abstract class ExecutorFixture(
      printLevel: Log.Level = Log.Level.Error
  )(
      implicit db: SQLiteStorage.CombinedStorage[Task]
  ) extends Fixture(length = Duration.Zero, printLevel = printLevel) {
    def test: Task[Unit]

    // Make a block that works with the MockValidator.
    // Make the body empty, otherwise it will fail because the
    // mock EE returns nothing, instead of the random stuff we have.
    def mockValidBlock = sample[Block].withBody(Block.Body()).pure[Task]

    // No validation by default.
    override def validation: Validation[Task] = new MockValidation[Task]

    // Collect emitted events.
    override implicit val eventEmitter: MockEventEmitter[Task] =
      MockEventEmitter.unsafe[Task]

    def validateAndAdd(block: Block): Task[Unit] =
      for {
        semaphore <- Semaphore[Task](1)
        _         <- messageExecutor.validateAndAdd(semaphore, block, isBookingBlock = false)
      } yield ()
  }

  behavior of "validateAndAdd"

  it should "raise an error if there's a problem with the block" in executorFixture { implicit db =>
    new ExecutorFixture(printLevel = Log.Level.Crit) {
      // Turn on validation.
      override def validation = new ValidationImpl[Task]()

      override def test =
        for {
          // A random block will not be valid.
          block  <- sample[Block].pure[Task]
          result <- validateAndAdd(block).attempt
        } yield {
          result match {
            case Left(ex)  => ex shouldBe a[ValidateErrorWrapper]
            case Right(()) => fail("Should have raised.")
          }
        }
    }
  }

  it should "not emit events" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def test =
        for {
          block  <- mockValidBlock
          _      <- validateAndAdd(block)
          events <- eventEmitter.events
        } yield {
          events shouldBe empty
        }

    }
  }

  it should "save the block itself" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def test =
        for {
          block <- mockValidBlock
          _     <- validateAndAdd(block)
          dag   <- DagStorage[Task].getRepresentation
          _     <- dag.contains(block.blockHash) shouldBeF true
          _     <- BlockStorage[Task].contains(block.blockHash) shouldBeF true
        } yield ()
    }
  }

  behavior of "effectsAfterAdded"

  it should "emit events" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def test =
        for {
          block   <- mockValidBlock
          message = Message.fromBlock(block).get
          _       <- BlockStorage[Task].put(block, BlockEffects.empty.effects)
          _       <- messageExecutor.effectsAfterAdded(message)
          events  <- eventEmitter.events
        } yield {
          forExactly(1, events) { event =>
            event.value.isBlockAdded shouldBe true
          }
        }
    }
  }

  it should "update the last finalized block" in executorFixture { implicit db =>
    new ExecutorFixture {

      val finalizerUpdatedRef = Ref.unsafe[Task, Boolean](false)

      override val finalizer = new MultiParentFinalizer[Task] {
        override def onNewMessageAdded(
            message: Message
        ) = finalizerUpdatedRef.set(true).as(none)
      }

      override def test =
        for {
          block   <- mockValidBlock
          message = Message.fromBlock(block).get
          _       <- BlockStorage[Task].put(block, BlockEffects.empty.effects)
          _       <- messageExecutor.effectsAfterAdded(message)
          _       <- finalizerUpdatedRef.get shouldBeF true
        } yield ()
    }
  }

}
