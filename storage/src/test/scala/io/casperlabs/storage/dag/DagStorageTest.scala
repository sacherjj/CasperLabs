package io.casperlabs.storage.dag

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.storage.{
  ArbitraryStorageData,
  BlockMsgWithTransform,
  SQLiteFixture,
  SQLiteStorage
}
import monix.eval.Task
import monix.execution.Scheduler
import org.scalacheck.Shrink
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

trait DagStorageTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll
    with ArbitraryStorageData {
  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    dagSize = 5,
    dagDepth = 3,
    dagBranchingFactor = 1,
    maxSessionCodeBytes = 1,
    maxPaymentCodeBytes = 1,
    minSessionCodeBytes = 1,
    minPaymentCodeBytes = 1
  )

  /** Needed only for keeping compatibility with previous 'storage' module custom ScalaCheck generators
    * [[FileDagStorageTest]] fails if use plain generators
    * */
  def zeroedRank(b: BlockMsgWithTransform): BlockMsgWithTransform =
    b.withBlockMessage(b.getBlockMessage.withHeader(b.getBlockMessage.getHeader.copy(rank = 0)))

  def zeroedRanks(bs: List[BlockMsgWithTransform]): List[BlockMsgWithTransform] = bs.map(zeroedRank)

  val scheduler = Scheduler.fixedPool("dag-storage-test-scheduler", 4)

  def withDagStorage[R](f: DagStorage[Task] => Task[R]): R

  "DAG Storage" should "be able to lookup a stored block" in {
    // NOTE: Expects that blocks.size == 2.
    // Updates 2nd block justification list to point at the 1st block.
    def updateLastMessageByValidator(
        blocks: List[BlockMsgWithTransform]
    ): List[BlockMsgWithTransform] =
      if (blocks.size == 1) {
        // That's the last station.
        blocks
      } else {
        val a = blocks(0)
        val b = blocks(1)
        List(
          b.update(
              _.blockMessage.update(
                _.header.validatorPublicKey := a.getBlockMessage.getHeader.validatorPublicKey
              )
            )
            .update(
              _.blockMessage.update(
                _.header.justifications := Seq(
                  Justification(
                    a.getBlockMessage.getHeader.validatorPublicKey,
                    a.getBlockMessage.blockHash
                  )
                )
              )
            )
        )
      }

    forAll(genBlockMsgWithTransformDagFromGenesis) { initial =>
      val validatorsToBlocks = initial
        .groupBy(_.getBlockMessage.getHeader.validatorPublicKey)
        .mapValues(_.sliding(2).flatMap(updateLastMessageByValidator))

      // Because we've updated validators' messages so that they always cite its previous block
      // we can just pick the `last` element in each of the validators' swimlanes as the "latest message".
      val latestBlocksByValidator = validatorsToBlocks.mapValues(msgs => Set(msgs.toList.last))
      val blockElements           = validatorsToBlocks.values.toList.flatten

      withDagStorage { dagStorage =>
        for {
          _ <- blockElements.traverse_(
                blockMsgWithTransform => dagStorage.insert(blockMsgWithTransform.getBlockMessage)
              )
          dag <- dagStorage.getRepresentation
          // Test that we can lookup all blocks that we've just inserted.
          _ <- blockElements.traverse {
                case BlockMsgWithTransform(Some(b), _) =>
                  dag.lookup(b.blockHash).map(_ shouldBe Message.fromBlock(b).toOption)
                case _ => ???
              }
          // Test that `latestMessageHash(validator)` and `latestMessage(validator)` return
          // expected results.
          _ <- latestBlocksByValidator.toList.traverse {
                case (validator, latestBlocks) =>
                  for {
                    latestMessageHash <- dag.latestMessageHash(validator)
                    latestMessage     <- dag.latestMessage(validator)
                  } yield {
                    latestMessage should contain theSameElementsAs latestBlocks
                      .map(_.getBlockMessage)
                      .map(
                        Message
                          .fromBlock(_)
                          .get
                      )

                    latestMessageHash should contain theSameElementsAs latestBlocks
                      .map(
                        _.getBlockMessage.blockHash
                      )
                  }
                case _ => ???
              }
          _ <- dag.latestMessageHashes.map { got =>
                got.toList should contain theSameElementsAs latestBlocksByValidator
                  .mapValues(
                    _.map(_.getBlockMessage.blockHash).toSet
                  )
              }
          _ <- dag.latestMessages.map { got =>
                val expected = latestBlocksByValidator
                  .mapValues(
                    _.map(_.getBlockMessage).map(Message.fromBlock(_).get)
                  )
                got.toList should contain theSameElementsAs expected.toList
              }
        } yield ()
      }
    }
  }

  "DAG Storage" should "be able to properly (de)serialize data" in {
    forAll { b: Block =>
      withDagStorage { storage =>
        val before = BlockSummary.fromBlock(b).toByteArray
        for {
          _                 <- storage.insert(b)
          dag               <- storage.getRepresentation
          messageSummaryOpt <- dag.lookup(b.blockHash)
          _ <- Task {
                messageSummaryOpt should not be None
                val got = messageSummaryOpt.get.blockSummary.toByteArray
                assert(before.sameElements(got))
              }
        } yield ()
      }
    }
  }
}

class SQLiteDagStorageTest extends DagStorageTest with SQLiteFixture[DagStorage[Task]] {
  override def withDagStorage[R](f: DagStorage[Task] => Task[R]): R = runSQLiteTest[R](f)

  override def db: String = "/tmp/dag_storage.db"

  override def createTestResource: Task[DagStorage[Task]] =
    SQLiteStorage.create[Task]()

  "SQLite DAG Storage" should "override validator's latest block hash only if new messages quotes the previous one" in {
    forAll { (initial: Block, a: Block, c: Block) =>
      withDagStorage { storage =>
        def update(b: Block, validator: ByteString, prevHash: ByteString): Block =
          b.update(_.header.validatorPublicKey := validator)
            .update(_.header.justifications := Seq(Justification(validator, prevHash)))

        val validator = initial.validatorPublicKey
        // Block from the same validator that cites its previous block.
        // Should replace `validator_latest_message` entry in the database.
        val nextBlock = update(a, validator, initial.blockHash)
        // Block from the same validator that doesn't cite its previous block.
        // This is an equivocation. Should not replace `validator_latest_message` entry in the database but add a new one.
        val equivBlock = update(c, validator, ByteString.EMPTY)

        val readLatestMessages = storage.getRepresentation.flatMap(
          dag =>
            (
              dag.latestMessageHashes,
              dag.latestMessages,
              dag.latestMessage(validator),
              dag.latestMessageHash(validator)
            ).mapN((_, _, _, _))
        )

        for {
          _ <- storage.insert(initial)
          _ <- storage.insert(nextBlock)
          _ <- readLatestMessages.map {
                case (
                    latestMessageHashesGot,
                    latestMessagesGot,
                    validatorLatestMessagesGot,
                    validatorLatestMessageHashGot
                    ) =>
                  val validatorLatestMessages      = Set(Message.fromBlock(nextBlock).get)
                  val validatorLatestMessageHashes = validatorLatestMessages.map(_.messageHash)
                  latestMessageHashesGot shouldBe Map(validator -> validatorLatestMessageHashes)
                  latestMessagesGot shouldBe Map(validator      -> validatorLatestMessages)
                  validatorLatestMessagesGot shouldBe validatorLatestMessages
                  validatorLatestMessageHashGot shouldBe validatorLatestMessageHashes
              }

          _ <- storage.insert(equivBlock)
          // Equivocating block didn't include the `initial` one in its justifications,
          // both are validator's "latest messages"
          _ <- readLatestMessages.map {
                case (
                    latestMessageHashesGot,
                    latestMessagesGot,
                    validatorLatestMessageGot,
                    validatorLatestMessageHashGot
                    ) =>
                  val validatorLatestMessages =
                    Set(Message.fromBlock(nextBlock).get, Message.fromBlock(equivBlock).get)
                  val validatorLatestMessageHashes = validatorLatestMessages.map(_.messageHash)
                  latestMessageHashesGot shouldBe Map(validator -> validatorLatestMessageHashes)
                  latestMessagesGot shouldBe Map(
                    validator -> validatorLatestMessages
                  )
                  validatorLatestMessageGot shouldBe validatorLatestMessages
                  validatorLatestMessageHashGot shouldBe validatorLatestMessageHashes
              }
        } yield ()
      }
    }
  }
}
