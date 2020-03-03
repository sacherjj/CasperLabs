package io.casperlabs.storage.dag

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.{Block, BlockSummary, Era}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.storage.{
  ArbitraryStorageData,
  BlockMsgWithTransform,
  SQLiteFixture,
  SQLiteStorage
}
import monix.eval.Task
import monix.execution.Scheduler
import org.scalacheck.Arbitrary.arbitrary
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
    b.withBlockMessage(b.getBlockMessage.withHeader(b.getBlockMessage.getHeader.copy(jRank = 0)))

  def zeroedRanks(bs: List[BlockMsgWithTransform]): List[BlockMsgWithTransform] = bs.map(zeroedRank)

  val scheduler = Scheduler.fixedPool("dag-storage-test-scheduler", 4)

  def withDagStorage[R](f: DagStorage[Task] with EraStorage[Task] => Task[R]): R

  def setParent(p: Era)(e: Era): Era =
    e.withParentKeyBlockHash(p.keyBlockHash)
      .withStartTick(p.endTick)
      .withEndTick(p.endTick + (e.endTick - e.startTick))

  def setEra(e: Era)(b: Block): Block =
    b.update(_.header.keyBlockHash := e.keyBlockHash)
      .update(_.header.roundId := e.startTick)

  def setRoundId(r: Long)(b: Block): Block =
    b.update(_.header.roundId := r)

  def setPrev(p: Block)(b: Block): Block =
    b.update(_.header.validatorPublicKey := p.getHeader.validatorPublicKey)
      .update(_.header.validatorPrevBlockHash := p.blockHash)
      .update(
        _.header.justifications := Seq(Justification(p.getHeader.validatorPublicKey, p.blockHash))
      )

  behavior of "DAG Storage"

  it should "be able to lookup a stored block" in {
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
              _.blockMessage.update(block => {
                block.header.justifications := Seq(
                  Justification(
                    a.getBlockMessage.getHeader.validatorPublicKey,
                    a.getBlockMessage.blockHash
                  )
                )
                block.header.validatorPrevBlockHash := a.getBlockMessage.blockHash
              })
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
          tip <- dag.latestGlobal
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
                    latestMessageHash <- tip.latestMessageHash(validator)
                    latestMessage     <- tip.latestMessage(validator)
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
          _ <- tip.latestMessageHashes.map { got =>
                got.toList should contain theSameElementsAs latestBlocksByValidator
                  .mapValues(
                    _.map(_.getBlockMessage.blockHash).toSet
                  )
              }
          _ <- tip.latestMessages.map { got =>
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

  it should "be able to properly (de)serialize data" in {
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

  it should "override validator's latest block hash only if new messages quotes the previous one" in {
    forAll { (initial: Block, a: Block, c: Block) =>
      withDagStorage { storage =>
        def update(b: Block, validator: ByteString, prevHash: ByteString): Block =
          b.update(_.header.validatorPublicKey := validator)
            .update(_.header.justifications := Seq(Justification(validator, prevHash)))
            .update(_.header.validatorPrevBlockHash := prevHash)

        val validator = initial.validatorPublicKey
        // Block from the same validator that cites its previous block.
        // Should replace `validator_latest_message` entry in the database.
        val nextBlock = update(a, validator, initial.blockHash)
        // Block from the same validator that doesn't cite its previous block.
        // This is an equivocation. Should not replace `validator_latest_message` entry in the database but add a new one.
        val equivBlock = update(c, validator, ByteString.EMPTY)

        val readLatestMessages = storage.getRepresentation
          .flatMap(_.latestGlobal)
          .flatMap(
            tip =>
              (
                tip.latestMessageHashes,
                tip.latestMessages,
                tip.latestMessage(validator),
                tip.latestMessageHash(validator)
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

  it should "not propagate the validator's latest block to child eras" in {
    val data = for {
      // Era tree:
      // e0 - e1   e3
      //    \    /
      //      e2 - e4
      //         \
      //           e5
      e0 <- arbitrary[Era]
      e1 <- arbitrary[Era] map setParent(e0)
      e2 <- arbitrary[Era] map setParent(e0)
      e3 <- arbitrary[Era] map setParent(e2)
      e4 <- arbitrary[Era] map setParent(e2)
      e5 <- arbitrary[Era] map setParent(e2)
      // Blocks in era 2
      b20 <- arbitrary[Block] map setEra(e2)
      b21 <- arbitrary[Block] map setEra(e2) map setPrev(b20)
      // A ballot after the era.
      b22 <- arbitrary[Block] map setEra(e2) map setPrev(b21) map setRoundId(e2.endTick) map {
              _.update(_.header.messageType := Block.MessageType.BALLOT)
            }
      // A block in era 4
      b41 <- arbitrary[Block] map setEra(e4) map setPrev(b21)
      // An equivocation in era 5, because they don't form a chain of justifications
      b51 <- arbitrary[Block] map setEra(e5) map setPrev(b20)
      b52 <- arbitrary[Block] map setEra(e5) map setPrev(b21)
      // An equivocation in era 2, because it doesn't cite b22
      b23 <- arbitrary[Block] map setEra(e2) map setPrev(b21)
    } yield List(e0, e1, e2, e3, e4, e5) -> List(b20, b21, b22, b41, b51, b52, b23)

    forAll(data) {
      case (eras: List[Era], blocks @ List(_, _, b22, b41, b51, b52, b23)) =>
        withDagStorage { storage =>
          def latestMessageHashes(eraIdx: Int) =
            storage.getRepresentation.flatMap { dag =>
              dag
                .latestInEra(eras(eraIdx).keyBlockHash)
                .flatMap(_.latestMessageHashes)
            }

          val v = blocks.head.getHeader.validatorPublicKey

          for {
            _ <- eras.traverse(storage.addEra)
            _ <- blocks.traverse(storage.insert)

            // Not in the parent.
            lmh0 <- latestMessageHashes(0)
            _    = lmh0 shouldBe empty

            // Not in a sibling.
            lmh1 <- latestMessageHashes(1)
            _    = lmh1 shouldBe empty

            // The voting ballot in the era itself, plus the equivocation
            lmh2 <- latestMessageHashes(2)
            _    = lmh2(v) shouldBe Set(b22.blockHash, b23.blockHash)

            // Not in an empty child
            lmh3 <- latestMessageHashes(3)
            _    = lmh3 shouldBe empty

            // The block created in the child era.
            lmh4 <- latestMessageHashes(4)
            _    = lmh4(v) shouldBe Set(b41.blockHash)

            // Both blocks that didn't cite each other
            lmh5 <- latestMessageHashes(5)
            _    = lmh5(v) shouldBe Set(b51.blockHash, b52.blockHash)

            // Overall there are 5 tips
            lmh <- storage.getRepresentation.flatMap(_.latestGlobal).flatMap(_.latestMessageHashes)
            _   = lmh should have size 1
            _   = lmh(v) shouldBe List(b23, b52, b51, b41, b22).map(_.blockHash).toSet
          } yield ()
        }
    }
  }

  it should "not inherit the parent era latest messages onto the child era" in {
    val data = for {
      e0 <- arbitrary[Era]
      e1 <- arbitrary[Era].map(setParent(e0))
      b0 <- arbitrary[Block].map(setEra(e0))
    } yield (e0, e1, b0)

    forAll(data) {
      case (e0, e1, b0) =>
        withDagStorage { storage =>
          for {
            _ <- storage.addEra(e0)
            _ <- storage.insert(b0)
            _ <- storage.addEra(e1)

            dag <- storage.getRepresentation
            tip <- dag.latestInEra(e1.keyBlockHash)

            lmh <- tip.latestMessageHashes
            _   = lmh shouldBe empty
          } yield ()
        }
    }
  }

  it should
    """
      |return blocks that conforms the requirements:
      |1) Produced only by a specified validator.
      |2) Amount of returned block must not exceed the 'limit' parameter.
      |3) Return only blocks with timestamps less or equal than 'lastTimeStamp' parameter.
      |4) If a block has the same timestamp as the 'lastTimeStamp' parameter then its hash must be less than the 'lastBlockHash' parameter.
      |   Comparison logic is the same as 'memcmp' function from C standard library
      |5) Returned blocks must be sorted by timestamps and hashes in decreasing order
      |""".stripMargin in {
    val validator1    = sample(genHash)
    val validator2    = sample(genHash)
    val limit         = 3
    val lastTimeStamp = 2L
    val lastBlockHash = ByteString.copyFrom(Base16.decode("ff" * 31 + "fe"))
    // Must be ignored because block_hash is equal to the lastBlockHash
    val block1 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator1)
      .update(_.header.timestamp := lastTimeStamp - 2L)
      .update(_.blockHash := lastBlockHash)
    // Must be ignored because block_hash is greater than the lastBlockHash
    val block2 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator1)
      .update(_.header.timestamp := lastTimeStamp - 2L)
      .update(_.blockHash := ByteString.copyFrom(Base16.decode("ff" * 32)))
    // Must be ignored because timestamp is greater than the lastTimeStamp
    val block3 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator1)
      .update(_.header.timestamp := lastTimeStamp + 1L)
    // Must be ignored because created by a different validator
    val block4 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator2)
    // Must be included into a response and must be the first because
    // if blocks' timestamp equal to the lastTimeStamp then they're sorted by their hashes in decreasing order
    val block5 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator1)
      .update(_.header.timestamp := lastTimeStamp)
      .update(_.blockHash := ByteString.copyFrom(Base16.decode("ff" * 31 + "fd")))
    // Must be included into a response and must be the second because
    // if blocks' timestamp equal to the lastTimeStamp then they're sorted by their hashes in decreasing order
    val block6 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator1)
      .update(_.header.timestamp := lastTimeStamp)
      .update(_.blockHash := ByteString.copyFrom(Base16.decode("ff" * 31 + "fc")))
    // Must be included into a response and must be the third because
    // its timestamp less than lastTimeStamp
    val block7 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator1)
      .update(_.header.timestamp := lastTimeStamp - 1)
    // Must be ignored because we limit for 3 blocks at most and results sorted by decreasing order by timestamps
    // There are block5 and block6 with the timestamp = 1
    val block8 = sample(arbitrary[Block])
      .update(_.header.validatorPublicKey := validator1)
      .update(_.header.timestamp := lastTimeStamp - 2L)

    withDagStorage { storage =>
      for {
        _   <- storage.insert(block1)
        _   <- storage.insert(block2)
        _   <- storage.insert(block3)
        _   <- storage.insert(block4)
        _   <- storage.insert(block5)
        _   <- storage.insert(block6)
        _   <- storage.insert(block7)
        _   <- storage.insert(block8)
        dag <- storage.getRepresentation
        List(b1, b2, b3) <- dag.getBlockInfosByValidator(
                             validator = validator1,
                             limit = limit,
                             lastTimeStamp = lastTimeStamp,
                             lastBlockHash = lastBlockHash
                           )
      } yield {
        b1.getSummary.blockHash shouldBe block5.blockHash
        b1.getSummary.validatorPublicKey shouldBe validator1
        b1.getSummary.timestamp shouldBe lastTimeStamp

        b2.getSummary.blockHash shouldBe block6.blockHash
        b2.getSummary.validatorPublicKey shouldBe validator1
        b2.getSummary.timestamp shouldBe lastTimeStamp

        b3.getSummary.blockHash shouldBe block7.blockHash
        b3.getSummary.validatorPublicKey shouldBe validator1
        b3.getSummary.timestamp shouldBe lastTimeStamp - 1
      }
    }
  }
}

class SQLiteDagStorageTest
    extends DagStorageTest
    with SQLiteFixture[DagStorage[Task] with EraStorage[Task]] {
  override def withDagStorage[R](f: DagStorage[Task] with EraStorage[Task] => Task[R]): R =
    runSQLiteTest[R](f)

  override def db: String = "/tmp/dag_storage.db"

  override def createTestResource: Task[DagStorage[Task] with EraStorage[Task]] =
    SQLiteStorage.create[Task](readXa = xa, writeXa = xa)
}
