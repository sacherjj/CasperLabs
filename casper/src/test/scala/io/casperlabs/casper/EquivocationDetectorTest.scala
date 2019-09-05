package io.casperlabs.casper

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStorage, DagRepresentation, IndexedDagStorage}
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Cell
import org.scalatest.{Assertion, FlatSpec, Matchers}
import monix.eval.Task

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
      _ <- (EquivocationDetector.checkEquivocationWithUpdate(dag, b).attempt shouldBeF Left(
            EquivocatedBlock
          )).whenA(result)
      _ <- (EquivocationDetector.checkEquivocationWithUpdate(dag, b) shouldBeF Valid
            .asRight[InvalidBlock]).whenA(!result)
      state <- Cell[Task, CasperState].read
      _     = state.equivocationsTracker.contains(b.getHeader.validatorPublicKey) shouldBe (result)
    } yield b

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

  "EquivocationDetector" should "work well when block indirect reference previous creator block" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * The Dag looks like
         *
         *    v0    |      v1     |
         *          |             |
         *          |    b5       |
         *          | /     \     |
         *         /|         |   |
         *       b4 |         |   |
         *        | |         |   |
         *       b3 |         |   |
         *         \|         |   |
         *          |----b2   |   |
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

}
