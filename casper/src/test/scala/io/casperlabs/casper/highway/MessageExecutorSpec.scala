package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.concurrent.{Ref, Semaphore}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.casper.consensus.state
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
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.casper.consensus.info.DeployInfo

class MessageExecutorSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  def executorFixture(f: SQLiteStorage.CombinedStorage[Task] => ExecutorFixture): Unit =
    withCombinedStorage() { db =>
      f(db).test
    }

  implicit val consensusConfig = ConsensusConfig()

  // Pick a key pair we can sign with.
  val randomValidator = sample(genAccountKeys)

  def signBlock(block: Block): Block = {
    val bodyHash  = protoHash(block.getBody)
    val header    = block.getHeader.withBodyHash(bodyHash)
    val blockHash = protoHash(header)
    block
      .withHeader(header)
      .withBlockHash(blockHash)
      .withSignature(randomValidator.sign(blockHash))
  }

  /** A fixture based on the Highway one where we won't be using the TestScheduler,
    * just call methods on the `messageExecutor` and override its dependencies to
    * turn validation on/off and capture the effects it carries out.
    */
  abstract class ExecutorFixture(
      printLevel: Log.Level = Log.Level.Error
  )(
      implicit db: SQLiteStorage.CombinedStorage[Task]
  ) extends Fixture(
        length = Duration.Zero,
        printLevel = printLevel,
        bonds = List(
          // Our validator needs to be bonded in Genesis.
          Bond(randomValidator.publicKey).withStake(state.BigInt("10000"))
        )
      ) {

    // Make a block that works with the MockValidator,
    // i.e. if we don't explicitly validate it it passes.
    val mockValidBlock = Task.eval {
      // Use something we can sign with.
      // Make the body empty, otherwise it will fail because the
      // mock EE returns nothing, instead of the random stuff we have.
      signBlock {
        val block = sample[Block]
        block
          .withBody(Block.Body())
          .withHeader(
            block.getHeader
              .withState(
                block.getHeader.getState.withBonds(genesisBlock.getHeader.getState.bonds)
              )
              .withChainName(chainName)
              .withValidatorPublicKey(randomValidator.publicKey)
              .withParentHashes(List(genesis.messageHash))
              .withKeyBlockHash(genesis.messageHash)
              .withJustifications(Seq.empty)
              .withRank(1)
              .withValidatorBlockSeqNum(1)
              .withValidatorPrevBlockHash(ByteString.EMPTY)
              .withDeployCount(0)
              .clearProtocolVersion
          )
      }
    }

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

  trait RealValidation { self: ExecutorFixture =>
    override def validation = new ValidationImpl[Task]()
  }

  behavior of "validateAndAdd"

  it should "raise an error for unattributable errors" in executorFixture { implicit db =>
    new ExecutorFixture(printLevel = Log.Level.Crit) with RealValidation {
      override def test =
        for {
          // A block without signature cannot be a slashed
          block  <- mockValidBlock.map(_.clearSignature)
          result <- validateAndAdd(block).attempt
          _ = result match {
            case Left(ex)  => ex shouldBe a[ValidateErrorWrapper]
            case Right(()) => fail("Should have raised.")
          }
          _ <- BlockStorage[Task].contains(block.blockHash) shouldBeF false
        } yield ()
    }
  }

  it should "not raise an error for unattributable errors" in executorFixture { implicit db =>
    new ExecutorFixture(printLevel = Log.Level.Crit) with RealValidation {
      override def test =
        for {
          _ <- insertGenesis()
          // Make a block that signed by the validator that has invalid content.
          block <- mockValidBlock.map { block =>
                    signBlock(block.withHeader(block.getHeader.withRank(100)))
                  }
          result <- validateAndAdd(block).attempt
          _ = result match {
            case Left(ex)  => fail(s"Shouldn't have raised: $ex")
            case Right(()) =>
          }
          _ <- BlockStorage[Task].contains(block.blockHash) shouldBeF true
        } yield ()
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
          block   <- sample[Block].pure[Task]
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
          block   <- sample[Block].pure[Task]
          message = Message.fromBlock(block).get
          _       <- BlockStorage[Task].put(block, BlockEffects.empty.effects)
          _       <- messageExecutor.effectsAfterAdded(message)
          _       <- finalizerUpdatedRef.get shouldBeF true
        } yield ()
    }
  }

  it should "mark deploys as finalized" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def test =
        for {
          block   <- sample(arbBlock.arbitrary.filter(_.getBody.deploys.size > 0)).pure[Task]
          deploys = block.getBody.deploys.map(_.getDeploy).toList
          _       <- DeployStorage[Task].writer.addAsPending(deploys)
          _       <- BlockStorage[Task].put(block, BlockEffects.empty.effects)
          message = Message.fromBlock(block).get
          _       <- messageExecutor.effectsAfterAdded(message)
          statuses <- deploys.traverse { d =>
                       DeployStorage[Task].reader.getBufferedStatus(d.deployHash)
                     }
        } yield {
          forAll(statuses) { maybeStatus =>
            maybeStatus should not be empty
            maybeStatus.get.state shouldBe DeployInfo.State.PROCESSED
          }
        }
    }
  }

}
