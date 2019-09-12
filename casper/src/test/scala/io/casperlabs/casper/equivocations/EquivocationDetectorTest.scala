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
      result: Boolean
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
      _ <- EquivocationDetector.checkEquivocations(dag, b) shouldBeF (result)
      blockStatus <- EquivocationDetector
                      .checkEquivocationWithUpdate(dag, b)
                      .attempt
      _ = if (result) {
        blockStatus shouldBe Left(ValidateErrorWrapper(EquivocatedBlock))
      } else {
        blockStatus shouldBe Right(())
      }
      state <- Cell[Task, CasperState].read
      _     = state.equivocationTracker.contains(b.getHeader.validatorPublicKey) shouldBe (result)
    } yield b

  def findEquivocatorFromViewOfBlock(
      block: Block,
      result: Set[Validator]
  )(
      implicit dagStorage: IndexedDagStorage[Task],
      blockStorage: BlockStorage[Task],
      casperState: Cell[Task, CasperState]
  ): Task[Unit] =
    for {
      dag            <- dagStorage.getRepresentation
      latestMessages <- ProtoUtil.toLatestMessage[Task](block.getHeader.justifications)
      state          <- casperState.read
      _ <- EquivocationDetector.equivocatorDetectFromLatestMessage(
            dag,
            latestMessages.mapValues(f => f.blockHash),
            state.equivocationTracker
          ) shouldBeF result
    } yield ()

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
                 result = false
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b1.blockHash),
                v0,
                justifications = HashMap(v0 -> b1.blockHash),
                result = false
              )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b1.blockHash),
                v0,
                justifications = HashMap(v0 -> b1.blockHash),
                result = true
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
                 result = false
               )
          b1 <- createBlockAndTestEquivocateDetector(
                 Seq(b0.blockHash),
                 v0,
                 justifications = HashMap(v1 -> b0.blockHash),
                 result = false
               )
          b2 <- createBlockAndTestEquivocateDetector(
                 Seq(b0.blockHash),
                 v1,
                 justifications = HashMap(v1 -> b0.blockHash),
                 result = false
               )
          b3 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b1.blockHash, v1 -> b2.blockHash),
                 result = false
               )
          b4 <- createBlockAndTestEquivocateDetector(
                 Seq(b3.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b3.blockHash),
                 result = false
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b3.blockHash),
                v0,
                justifications = HashMap(v0 -> b3.blockHash),
                result = true
              )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b4.blockHash),
                v1,
                justifications = HashMap(v0 -> b4.blockHash),
                result = false
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
                 result = false
               )
          b2 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v1,
                 justifications = HashMap(v1 -> b1.blockHash),
                 result = false
               )
          b3 <- createBlockAndTestEquivocateDetector(
                 Seq(b2.blockHash),
                 v0,
                 justifications = HashMap(v1 -> b2.blockHash),
                 result = false
               )
          b4 <- createBlockAndTestEquivocateDetector(
                 Seq(b3.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b3.blockHash),
                 result = false
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b4.blockHash),
                v1,
                justifications = HashMap(v0 -> b4.blockHash, v1 -> b1.blockHash),
                result = false
              )
        } yield ()
  }

  it should "checkEquivocations failed detecting equivocation when receiving a block created by a validator who has been detected as equivocator but checkEquivocationWithUpdate should work well." in withStorage {
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
                 result = false
               )
          _ <- createBlockAndTestEquivocateDetector(
                Seq(b1.blockHash),
                v0,
                justifications = HashMap(v0 -> b1.blockHash),
                result = false
              )
          b3 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b1.blockHash),
                 result = true
               )
          dag <- dagStorage.getRepresentation
          b4 <- createBlock[Task](
                 Seq(b3.blockHash),
                 v0,
                 justifications = HashMap(v0 -> b3.blockHash)
               )
          _ <- EquivocationDetector.checkEquivocations(dag, b4) shouldBeF false
          _ <- EquivocationDetector.checkEquivocationWithUpdate(dag, b4).attempt shouldBeF Left(
                EquivocatedBlock
              )
        } yield ()
  }

  "equivocatorDetectFromLatestMessage" should "" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * The Dag looks like
         *
         *     v0   |      v1     |
         *          |             |
         *          |         b6  |
         *          |         |   |
         *          |         |   |
         *          |         b5  |
         *          |     /   |   |
         *          |  /      |   |
         *         /|         |   |
         *     b4   |         |   |
         *         \|         |   |
         *          | \       |   |
         *          |   b2    b3  |
         *          |     \   /   |
         *          |      b1     |
         *                  \
         *                    genesis
         *
         */

        implicit val logEff: LogStub[Task] = new LogStub[Task]()
        val v0                             = generateValidator("V0")
        val v1                             = generateValidator("V1")

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          b1 <- createBlockAndTestEquivocateDetector(
                 Seq(genesis.blockHash),
                 v1,
                 justifications = HashMap(v1 -> genesis.blockHash),
                 result = false
               )
          b2 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v1,
                 justifications = HashMap(v1 -> b1.blockHash),
                 result = false
               )
          b3 <- createBlockAndTestEquivocateDetector(
                 Seq(b1.blockHash),
                 v1,
                 justifications = HashMap(v1 -> b1.blockHash),
                 result = true
               )
          _ <- findEquivocatorFromViewOfBlock(b3, Set.empty)
          b4 <- createBlockAndTestEquivocateDetector(
                 Seq(b2.blockHash),
                 v0,
                 justifications = HashMap(v1 -> b2.blockHash),
                 result = false
               )
          b5 <- createBlock[Task](
                 Seq(b3.blockHash),
                 v1,
                 justifications = HashMap(v0 -> b4.blockHash, v1 -> b3.blockHash)
               )
          _ <- findEquivocatorFromViewOfBlock(b5, Set(v1))
          b6 <- createBlock[Task](
                 Seq(b5.blockHash),
                 v1,
                 justifications = HashMap(v1 -> b5.blockHash)
               )
          _ <- findEquivocatorFromViewOfBlock(b6, Set(v1))
        } yield ()
  }
}
