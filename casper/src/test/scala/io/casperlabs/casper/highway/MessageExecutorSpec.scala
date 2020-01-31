package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.concurrent.{Ref, Semaphore}
import com.google.protobuf.ByteString
import io.casperlabs.casper.{DeploySelection, ValidatorIdentity}
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
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
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.shared.Log
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.ValidatorIdentity

class MessageExecutorSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  def executorFixture(f: SQLiteStorage.CombinedStorage[Task] => ExecutorFixture): Unit =
    withCombinedStorage() { db =>
      f(db).test
    }

  implicit val consensusConfig = ConsensusConfig()

  // Pick a key pair we can sign with.
  val testValidator = sample(genAccountKeys)

  // Resign a block with the test validator after we messed with its contents.
  def signBlock(block: Block): Block = {
    val bodyHash  = protoHash(block.getBody)
    val header    = block.getHeader.withBodyHash(bodyHash)
    val blockHash = protoHash(header)
    block
      .withHeader(header)
      .withBlockHash(blockHash)
      .withSignature(testValidator.sign(blockHash))
  }

  /** A fixture based on the Highway one where we won't be using the TestScheduler,
    * just call methods on the `messageExecutor` and override its dependencies to
    * turn validation on/off and capture the effects it carries out.
    */
  abstract class ExecutorFixture(
      printLevel: Log.Level = Log.Level.Error,
      validate: Boolean = false
  )(
      implicit db: SQLiteStorage.CombinedStorage[Task]
  ) extends Fixture(
        length = Duration.Zero,
        printLevel = printLevel,
        bonds = List(
          // Our validator needs to be bonded in Genesis.
          Bond(testValidator.publicKey).withStake(state.BigInt("10000"))
        )
      ) {

    // Make a message producer that's supposed to make valid blocks.
    def makeMessageProducer(keys: AccountKeys): MessageProducer[Task] = {
      implicit val deployBuffer    = DeployBuffer.create[Task](chainName, minTtl = Duration.Zero)
      implicit val deploySelection = DeploySelection.create[Task](sizeLimitBytes = Int.MaxValue)

      MessageProducer[Task](
        validatorIdentity = ValidatorIdentity(
          PublicKey(keys.publicKey.toByteArray),
          PrivateKey(keys.privateKey.toByteArray),
          signatureAlgorithm = Ed25519
        ),
        chainName = chainName,
        upgrades = Seq.empty
      )
    }

    // Make blocks in the test validator's name.
    override val messageProducer = makeMessageProducer(testValidator)

    // Produce the first block for the test validator validator, already inserted.
    def insertFirstBlock(): Task[Block] =
      for {
        _ <- insertGenesis()
        _ <- addGenesisEra()
        message <- messageProducer.block(
                    keyBlockHash = genesis.messageHash,
                    roundId = conf.toTicks(conf.genesisEraStart),
                    mainParent = genesis.messageHash,
                    justifications = Map.empty,
                    isBookingBlock = false
                  )
        block <- BlockStorage[Task].getBlockUnsafe(message.messageHash)
      } yield block

    // Make a second block without inserting it.
    def prepareSecondBlock(): Task[Block] =
      for {
        first <- insertFirstBlock()
        second = signBlock {
          first.withHeader(
            first.getHeader
              .withRank(first.getHeader.rank + 1)
              .withValidatorBlockSeqNum(first.getHeader.validatorBlockSeqNum + 1)
              .withValidatorPrevBlockHash(first.blockHash)
              .withParentHashes(List(first.blockHash))
              .withJustifications(
                List(Block.Justification(testValidator.publicKey, first.blockHash))
              )
          )
        }
      } yield second

    override def validation: Validation[Task] =
      if (validate) new ValidationImpl[Task]() else new MockValidation[Task]

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

  it should "validate and save a valid block" in executorFixture { implicit db =>
    new ExecutorFixture(validate = true, printLevel = Log.Level.Warn) {
      override def test =
        for {
          block <- prepareSecondBlock()
          _     <- validateAndAdd(block)
          dag   <- DagStorage[Task].getRepresentation
          _     <- dag.contains(block.blockHash) shouldBeF true
          _     <- BlockStorage[Task].contains(block.blockHash) shouldBeF true
        } yield ()
    }
  }

  it should "raise an error for unattributable errors" in executorFixture { implicit db =>
    new ExecutorFixture(validate = true, printLevel = Log.Level.Crit) {
      override def test =
        for {
          // A block without signature cannot be a slashed
          block  <- prepareSecondBlock().map(_.clearSignature)
          result <- validateAndAdd(block).attempt
          _ = result match {
            case Left(ex)  => ex shouldBe a[ValidateErrorWrapper]
            case Right(()) => fail("Should have raised.")
          }
          _ <- BlockStorage[Task].contains(block.blockHash) shouldBeF false
        } yield ()
    }
  }

  // TODO(CON-623): In the future we want these not to raise, but for that the fork
  // choice would also have to know not to build on them because they are invalid blocks.
  it should "raise an error for attributable errors" in executorFixture { implicit db =>
    new ExecutorFixture(validate = true, printLevel = Log.Level.Crit) {
      override def test =
        for {
          // Make a block that signed by the validator that has invalid content.
          block <- prepareSecondBlock() map { block =>
                    signBlock(block.withHeader(block.getHeader.withRank(100)))
                  }
          result <- validateAndAdd(block).attempt
          _ = result match {
            case Left(ex)  => ex shouldBe a[ValidateErrorWrapper]
            case Right(()) => fail("Should have raised.")
          }
          _ <- BlockStorage[Task].contains(block.blockHash) shouldBeF false
        } yield ()
    }
  }

  it should "not emit events" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def test =
        for {
          block  <- prepareSecondBlock()
          _      <- validateAndAdd(block)
          events <- eventEmitter.events
        } yield {
          events shouldBe empty
        }

    }
  }

  behavior of "effectsAfterAdded"

  it should "emit events" in executorFixture { implicit db =>
    new ExecutorFixture {
      override def test =
        for {
          block   <- insertFirstBlock()
          message = Message.fromBlock(block).get
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
          block   <- insertFirstBlock()
          message = Message.fromBlock(block).get
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
