package io.casperlabs.storage.dag

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.storage.{ArbitraryStorageData, BlockMsgWithTransform, SQLiteFixture, SQLiteStorage}
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
    def setRank(blockMsg: BlockMsgWithTransform, rank: Int): BlockMsgWithTransform =
      blockMsg.withBlockMessage(
        blockMsg.getBlockMessage.withHeader(
          blockMsg.getBlockMessage.getHeader.withRank(rank.toLong)
        )
      )

    forAll(genBlockMsgWithTransformDagFromGenesis) { initial =>
      val validatorsToBlocks = initial
        .groupBy(_.getBlockMessage.getHeader.validatorPublicKey)
        .mapValues(_.zipWithIndex.map { case (block, i) => setRank(block, i) })
      val latestBlocksByValidator = validatorsToBlocks.mapValues(_.maxBy(_.getBlockMessage.rank))
      val blockElements           = validatorsToBlocks.values.toList.flatten

      withDagStorage { dagStorage =>
        for {
          _ <- blockElements.traverse_(
                blockMsgWithTransform => dagStorage.insert(blockMsgWithTransform.getBlockMessage)
              )
          dag <- dagStorage.getRepresentation
          _ <- blockElements.traverse {
                case BlockMsgWithTransform(Some(b), _) =>
                  for {
                    blockSummary <- dag.lookup(b.blockHash)
                  } yield blockSummary shouldBe Message.fromBlock(b).toOption
                case _ => ???
              }
          _ <- latestBlocksByValidator.toList.traverse {
                case (validator, BlockMsgWithTransform(Some(b), _)) =>
                  for {
                    latestMessageHash <- dag.latestMessageHash(validator)
                    latestMessage     <- dag.latestMessage(validator)
                  } yield {
                    latestMessageHash shouldBe Some(b.blockHash)
                    latestMessage shouldBe Message.fromBlock(b).toOption
                  }
                case _ => ???
              }
          _ <- dag.latestMessageHashes.map { got =>
                got.toList should contain theSameElementsAs latestBlocksByValidator.toList.map {
                  case (v, b) => (v, b.getBlockMessage.blockHash)
                }
              }
          _ <- dag.latestMessages.map { got =>
                got.toList should contain theSameElementsAs latestBlocksByValidator.toList.map {
                  case (v, b) => (v, Message.fromBlock(b.getBlockMessage).get)
                }
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

  "SQLite DAG Storage" should "override validator's latest block hash only if rank is higher" in {
    forAll { (initial: Block, a: Block, c: Block) =>
      withDagStorage { storage =>
        def update(b: Block, validator: ByteString, rank: Long): Block =
          b.withHeader(b.getHeader.withValidatorPublicKey(validator).withRank(rank))

        val validator  = initial.validatorPublicKey
        val rank       = initial.rank
        val lowerRank  = update(a, validator, rank - 1)
        val higherRank = update(c, validator, rank + 1)

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
          (
            latestMessageHashesBefore,
            latestMessagesBefore,
            latestMessageBefore,
            latestMessageHashBefore
          ) <- readLatestMessages

          _ <- storage.insert(lowerRank)
          // block with lower rank should be ignored
          _ <- readLatestMessages.map {
                case (
                    latestMessageHashesGot,
                    latestMessagesGot,
                    latestMessageGot,
                    latestMessageHashGot
                    ) =>
                  latestMessageHashesGot shouldBe latestMessageHashesBefore
                  latestMessagesGot shouldBe latestMessagesBefore
                  latestMessageGot shouldBe latestMessageBefore
                  latestMessageHashGot shouldBe latestMessageHashBefore
              }

          // not checking new blocks with same rank because they should be invalidated
          // before storing because of equivocation

          _ <- storage.insert(higherRank)
          // only block with higher rank should override previous message
          _ <- readLatestMessages.map {
                case (
                    latestMessageHashesGot,
                    latestMessagesGot,
                    latestMessageGot,
                    latestMessageHashGot
                    ) =>
                  latestMessageHashesGot shouldBe Map(validator -> higherRank.blockHash)
                  latestMessagesGot shouldBe Map(
                    validator -> Message.fromBlock(higherRank).get
                  )
                  latestMessageGot shouldBe Message.fromBlock(higherRank).toOption
                  latestMessageHashGot shouldBe Some(higherRank.blockHash)
              }
        } yield ()
      }
    }
  }
}
