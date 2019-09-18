package io.casperlabs.casper.equivocations

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStorage, IndexedDagStorage}
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Cell
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

class EquivocationDetectorTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with DagStorageFixture {

  implicit val raiseValidateErr = validation.raiseValidateErrorThroughApplicativeError[Task]
  // Necessary because errors are returned via Sync which has an error type fixed to _ <: Throwable.
  // When raise errors we wrap them with Throwable so we need to do the same here.
  implicit def wrapWithThrowable[A <: InvalidBlock](err: A): Throwable =
    ValidateErrorWrapper(err)

  def createBlockAndTestEquivocateDetector(
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      rankOfLowestBaseBlockExpect: Option[Long]
  )(
      implicit dagStorage: IndexedDagStorage[Task],
      blockStorage: BlockStorage[Task],
      casperState: Cell[Task, CasperState],
      log: LogStub[Task]
  ): Task[Block] =
    for {
      dag <- dagStorage.getRepresentation
      b <- createBlock[Task](
            parentsHashList,
            creator,
            justifications = justifications
          )
      blockStatus <- EquivocationDetector
                      .checkEquivocationWithUpdate(dag, b)
                      .attempt

      _ = rankOfLowestBaseBlockExpect match {
        case None =>
          blockStatus shouldBe Right(())
        case Some(_) =>
          blockStatus shouldBe Left(ValidateErrorWrapper(EquivocatedBlock))
      }
      state <- Cell[Task, CasperState].read
      _     = state.equivocationsTracker.get(b.getHeader.validatorPublicKey) shouldBe rankOfLowestBaseBlockExpect
    } yield b

  def createBlockAndCheckEquivocatorsFromViewOfBlock(
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      rankOfLowestBaseBlockExpect: Option[Long],
      visibleEquivocatorExpected: Set[Validator]
  )(
      implicit dagStorage: IndexedDagStorage[Task],
      blockStorage: BlockStorage[Task],
      casperState: Cell[Task, CasperState],
      log: LogStub[Task]
  ): Task[Block] =
    for {
      block <- createBlockAndTestEquivocateDetector(
                parentsHashList,
                creator,
                justifications,
                rankOfLowestBaseBlockExpect
              )
      dag            <- dagStorage.getRepresentation
      latestMessages <- ProtoUtil.getJustificationMsgs[Task](dag, block.getHeader.justifications)
      state          <- casperState.read
      _ <- EquivocationDetector.detectVisibleFromJustifications(
            dag,
            latestMessages.mapValues(f => f.blockHash),
            state.equivocationsTracker
          ) shouldBeF visibleEquivocatorExpected
    } yield block

  "EquivocationDetector" should "detect simple equivocation" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * The Dag looks like
         *
         *     |      v0     |
         *     |             |
         *     |             |
         *     |    b2   b3  |
         *     |     \  /    |
         *     |      b1     |
         *             \
         *               genesis
         *
         */
        implicit val logEff: LogStub[Task] = new LogStub[Task]()
        val v0                             = generateValidator("V0")

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          b1 <- createBlockAndTestEquivocateDetector(
                 Seq(genesis.blockHash),
                 v0,
                 justifications = HashMap(v0 -> genesis.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b1.blockHash),
                v0,
                justifications = HashMap(v0 -> b1.blockHash),
                rankOfLowestBaseBlockExpect = None
              )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b1.blockHash),
                v0,
                justifications = HashMap(v0 -> b1.blockHash),
                rankOfLowestBaseBlockExpect = b1.getHeader.rank.some
              )
        } yield ()
  }

  it should "not report equivocation when reference a message creating an equivocation that was created by other validator" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * The Dag looks like
         *
         *    v0    |    v1     |
         *          |           |
         *          |    b6     |
         *          |  /        |
         *         /|           |
         *       /  |           |
         * b5   b4  |           |
         *   \ /    |           |
         *    b3    |           |
         *     | \  |           |
         *     |   \|           |
         *     |    | \         |
         *    b1    |    b2     |
         *        \ |    |
         *          | \  |
         *          |    b0
         *               /
         *         genesis
         *
         */
        implicit val logEff = new LogStub[Task]()
        val v0              = generateValidator("V0")
        val v1              = generateValidator("V1")
        for {
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
          b0 <- createBlockAndTestEquivocateDetector(
                 Seq(genesis.blockHash),
                 v1,
                 justifications = HashMap(v1 -> genesis.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b1 <- createBlockAndTestEquivocateDetector(
                 Seq(b0.blockHash),
                 v0,
                 justifications = HashMap(v1 -> b0.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b2 <- createBlockAndTestEquivocateDetector(
                 Seq(b0.blockHash),
                 v1,
                 justifications = HashMap(v1 -> b0.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b3 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b1.blockHash, v1 -> b2.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b4 <- createBlockAndTestEquivocateDetector(
                 Seq(b3.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b3.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b3.blockHash),
                v0,
                justifications = HashMap(v0 -> b3.blockHash),
                rankOfLowestBaseBlockExpect = b3.getHeader.rank.some
              )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b4.blockHash),
                v1,
                justifications = HashMap(v0 -> b4.blockHash),
                rankOfLowestBaseBlockExpect = None
              )
        } yield ()
  }

  "EquivocationDetector" should "not report equivocation when block indirectly references previous creator's block" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * The Dag looks like
         *
         *    v0    |      v1     |
         *          |             |
         *          |      b5     |
         *          |  /     \    |
         *         /|         |   |
         *       /  |         |   |
         *    b4    |         |   |
         *     |    |         |   |
         *    b3    |         |   |
         *       \  |         |   |
         *         \|----b2   |   |
         *          |     \   /   |
         *          |      b1     |
         *                  \
         *                    genesis
         *
         */
        implicit val logEff = new LogStub[Task]()
        val v0              = generateValidator("V0")
        val v1              = generateValidator("V1")
        for {
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
          b1 <- createBlockAndTestEquivocateDetector(
                 Seq(genesis.blockHash),
                 v1,
                 justifications = HashMap(v1 -> genesis.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b2 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v1,
                 justifications = HashMap(v1 -> b1.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b3 <- createBlockAndTestEquivocateDetector(
                 Seq(b2.blockHash),
                 v0,
                 justifications = HashMap(v1 -> b2.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b4 <- createBlockAndTestEquivocateDetector(
                 Seq(b3.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b3.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b4.blockHash),
                v1,
                justifications = HashMap(v0 -> b4.blockHash, v1 -> b1.blockHash),
                rankOfLowestBaseBlockExpect = None
              )
        } yield ()
  }

  it should "should detect equivocation when receiving a block created by a validator who has been detected equivocating" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * The Dag looks like
         *
         *          |      v0     |
         *          |             |
         *          |             |
         *          |         b4  |
         *          |         |   |
         *          |    b2   b3  |
         *          |     \   /   |
         *          |      b1     |
         *                  \
         *                    genesis
         *
         */

        implicit val logEff: LogStub[Task] = new LogStub[Task]()
        val v0                             = generateValidator("V0")

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          b1 <- createBlockAndTestEquivocateDetector(
                 Seq(genesis.blockHash),
                 v0,
                 justifications = HashMap(v0 -> genesis.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b1.blockHash),
                v0,
                justifications = HashMap(v0 -> b1.blockHash),
                rankOfLowestBaseBlockExpect = None
              )
          b3 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b1.blockHash),
                 rankOfLowestBaseBlockExpect = b1.getHeader.rank.some
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b3.blockHash),
                v0,
                justifications = HashMap(v0 -> b3.blockHash),
                rankOfLowestBaseBlockExpect = b1.getHeader.rank.some
              )
        } yield ()
  }

  it should "detect equivocation and update the rank of lowest base block correctly when receiving a block created by a validator who has been detected equivocating" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * The Dag looks like
         *
         *          |      v0     |
         *          |             |
         *          |             |
         *          |             |
         *          |             |
         *          |    b3   b4  |
         *          |     \   /   |
         *          |      b2  b6 |
         *          |      |  /   |
         *          |      b1     |
         *                  \
         *                    genesis
         *
         */

        implicit val logEff: LogStub[Task] = new LogStub[Task]()
        val v0                             = generateValidator("V0")

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          b1 <- createBlockAndTestEquivocateDetector(
                 Seq(genesis.blockHash),
                 v0,
                 justifications = HashMap(v0 -> genesis.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          b2 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b1.blockHash),
                 rankOfLowestBaseBlockExpect = None
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b2.blockHash),
                v0,
                justifications = HashMap(v0 -> b2.blockHash),
                rankOfLowestBaseBlockExpect = None
              )
          // When v0 creates first equivocation, then the rank of lowest base block is the rank of b2
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b2.blockHash),
                v0,
                justifications = HashMap(v0 -> b2.blockHash),
                rankOfLowestBaseBlockExpect = b2.getHeader.rank.some
              )
          // When v0 creates another equivocation, and the base block(i.e. block b1) of the
          // equivocation is smaller, then update the rank of lowest base block to be the rank of b1
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b1.blockHash),
                v0,
                justifications = HashMap(v0 -> b1.blockHash),
                rankOfLowestBaseBlockExpect = b1.getHeader.rank.some
              )
        } yield ()
  }

  // See [[casper/src/test/resources/casper/tipsHavingEquivocating.png]]
  "detectVisibleFromJustificationMsgHashes" should "find validators who has equivocated from the j-past-cone of block's justifications" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      implicit val logEff: LogStub[Task] = new LogStub[Task]()
      val v1                             = generateValidator("V1")
      val v2                             = generateValidator("V2")

      for {
        implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                            CasperState()
                                                          )
        genesis <- createBlockAndCheckEquivocatorsFromViewOfBlock(
                    Seq(),
                    ByteString.EMPTY,
                    rankOfLowestBaseBlockExpect = None,
                    visibleEquivocatorExpected = Set.empty
                  )
        a1 <- createBlockAndCheckEquivocatorsFromViewOfBlock(
               Seq(genesis.blockHash),
               v1,
               justifications = HashMap(v1 -> genesis.blockHash),
               rankOfLowestBaseBlockExpect = None,
               visibleEquivocatorExpected = Set.empty
             )
        a2 <- createBlockAndCheckEquivocatorsFromViewOfBlock(
               Seq(genesis.blockHash),
               v1,
               justifications = HashMap(v1 -> genesis.blockHash),
               rankOfLowestBaseBlockExpect = 0L.some,
               visibleEquivocatorExpected = Set.empty
             )
        b <- createBlockAndCheckEquivocatorsFromViewOfBlock(
              Seq(a2.blockHash),
              v2,
              justifications = HashMap(v1 -> a2.blockHash, v2 -> genesis.blockHash),
              rankOfLowestBaseBlockExpect = None,
              visibleEquivocatorExpected = Set.empty
            )
        c <- createBlockAndCheckEquivocatorsFromViewOfBlock(
              Seq(b.blockHash),
              v1,
              justifications = HashMap(v1 -> a1.blockHash, v2 -> b.blockHash),
              rankOfLowestBaseBlockExpect = 0L.some,
              visibleEquivocatorExpected = Set(v1)
            )
        // this block doesn't show in the diagram
        _ <- createBlockAndCheckEquivocatorsFromViewOfBlock(
              Seq(c.blockHash),
              v1,
              justifications = HashMap(v1 -> c.blockHash, v2 -> b.blockHash),
              rankOfLowestBaseBlockExpect = 0L.some,
              visibleEquivocatorExpected = Set(v1)
            )
      } yield ()
  }
}
