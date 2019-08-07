package io.casperlabs.casper

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.FinalityDetector.Committee
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

@silent("is never used")
class FinalityDetectorTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with DagStorageFixture {

  behavior of "Finality Detector"

  implicit val logEff = new LogStub[Task]

  it should "detect finality as appropriate" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /* The DAG looks like:
         *
         *   b8
         *   |  \
         *   |   \
         *   b6   b7
         *   |  x |
         *   b4   b5
         *   |  \ |
         *   b2  b3
         *    \ /
         *    b1
         *      \
         *       genesis
         */
        val v1     = generateValidator("Validator One")
        val v2     = generateValidator("Validator Two")
        val v1Bond = Bond(v1, 1)
        val v2Bond = Bond(v2, 1)
        val bonds  = Seq(v1Bond, v2Bond)

        implicit val finalityDetectorEffect = new FinalityDetectorBySingleSweepImpl[Task]

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          b1 <- createBlock[Task](
                 Seq(genesis.blockHash),
                 v1,
                 bonds,
                 HashMap(v1 -> genesis.blockHash)
               )
          b2 <- createBlock[Task](
                 Seq(b1.blockHash),
                 v1,
                 bonds
               )
          b3 <- createBlock[Task](
                 Seq(b1.blockHash),
                 v2,
                 bonds
               )
          b4 <- createBlock[Task](
                 Seq(b2.blockHash),
                 v1,
                 bonds,
                 HashMap(v1 -> b2.blockHash, v2 -> b3.blockHash)
               )
          b5 <- createBlock[Task](
                 Seq(b3.blockHash),
                 v2,
                 bonds,
                 HashMap(v2 -> b3.blockHash)
               )
          b6 <- createBlock[Task](
                 Seq(b4.blockHash),
                 v1,
                 bonds,
                 HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash)
               )
          b7 <- createBlock[Task](
                 Seq(b5.blockHash),
                 v2,
                 bonds,
                 HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash)
               )
          b8 <- createBlock[Task](
                 Seq(b6.blockHash),
                 v1,
                 bonds,
                 HashMap(v1 -> b6.blockHash, v2 -> b7.blockHash)
               )
          dag           <- dagStorage.getRepresentation
          levelZeroMsgs <- finalityDetectorEffect.levelZeroMsgs(dag, b1.blockHash, List(v1, v2))
          lowestLevelZeroMsgs = levelZeroMsgs.flatMap {
            case (_, msgs) => msgs.lastOption.map(_.blockHash)
          }.toSet
          _ = lowestLevelZeroMsgs shouldBe Set(b2.blockHash, b3.blockHash)
          _ <- dag.justificationToBlocks(b2.blockHash) shouldBeF Set(b4.blockHash)
          _ <- dag.justificationToBlocks(b3.blockHash) shouldBeF Set(b4.blockHash, b5.blockHash)
          _ <- dag.justificationToBlocks(b4.blockHash) shouldBeF Set(b6.blockHash, b7.blockHash)
          sweepResult <- finalityDetectorEffect.sweep(
                          dag,
                          Set(v1, v2),
                          levelZeroMsgs,
                          2,
                          2,
                          HashMap(v1 -> 1, v2 -> 1)
                        )
          committeeOpt <- finalityDetectorEffect.pruningLoop(
                           dag,
                           Set.empty,
                           levelZeroMsgs,
                           HashMap(v1 -> 1, v2 -> 1),
                           1
                         )
          _ = committeeOpt shouldBe None
          committeeopt <- finalityDetectorEffect.pruningLoop(
                           dag,
                           Set(v1, v2),
                           levelZeroMsgs,
                           HashMap(v1 -> 1, v2 -> 1),
                           1
                         )
          _ = committeeopt shouldBe Some(Committee(Set(v1, v2), 1L))
          committeeopt <- finalityDetectorEffect.pruningLoop(
                           dag,
                           Set(v1, v2),
                           levelZeroMsgs,
                           HashMap(v1 -> 1, v2 -> 1),
                           2
                         )
          _                              = committeeopt shouldBe Some(Committee(Set(v1, v2), 2L))
          (blockLevels, validatorLevels) = sweepResult
          _                              = blockLevels(b2.blockHash).blockLevel shouldBe (0)
          _                              = blockLevels(b3.blockHash).blockLevel shouldBe (0)
          _                              = blockLevels(b4.blockHash).blockLevel shouldBe (1)
          _                              = blockLevels(b5.blockHash).blockLevel shouldBe (0)
          _                              = blockLevels(b6.blockHash).blockLevel shouldBe (1)
          _                              = blockLevels(b7.blockHash).blockLevel shouldBe (1)
          _                              = blockLevels(b8.blockHash).blockLevel shouldBe (2)
          _                              = validatorLevels(v1) shouldBe (2)
          result                         = validatorLevels(v2) shouldBe (1)
          committee <- finalityDetectorEffect.findBestCommittee(
                        dag,
                        b1.blockHash,
                        Map(v1 -> 1, v2 -> 1)
                      )
          _ = committee shouldBe Some(Committee(Set(v1, v2), 2))
        } yield result
  }

  it should "take into account indirect justifications by non-level-zero direct justification" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      val v0 = generateValidator("Validator 0")
      val v1 = generateValidator("Validator 1")

      val v2         = generateValidator("Validator 2")
      val v3         = generateValidator("Validator 3")
      val validators = List(v0, v1, v2, v3)

      val bonds                           = validators.map(v => Bond(v, 1))
      implicit val finalityDetectorEffect = new FinalityDetectorBySingleSweepImpl[Task]

      /* The DAG looks like (|| means main parent)
       *
       *        v0  v1    v2  v3
       *
       *                  b7
       *                  ||
       *                  b6
       *                //   \
       *             //       b5
       *          //   /----/ ||
       *        b4  b3        ||
       *        || //         ||
       *        b1            b2
       *         \\         //
       *            genesis
       *
       */
      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
        b1 <- createBlock[Task](
               Seq(genesis.blockHash),
               v0,
               bonds,
               Map(v0 -> genesis.blockHash)
             )
        b2 <- createBlock[Task](
               Seq(genesis.blockHash),
               v3,
               bonds,
               Map(v3 -> genesis.blockHash)
             )
        b3 <- createBlock[Task](
               Seq(b1.blockHash),
               v1,
               bonds,
               Map(v0 -> b1.blockHash, v1 -> genesis.blockHash)
             )
        b4 <- createBlock[Task](
               Seq(b1.blockHash),
               v0,
               bonds,
               Map(v0 -> b1.blockHash)
             )
        // b5 vote for b2 instead of b1
        b5 <- createBlock[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               Map(v1 -> b3.blockHash, v3 -> b2.blockHash)
             )
        b6 <- createBlock[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               Map(v0 -> b4.blockHash, v2 -> genesis.blockHash, v3 -> b5.blockHash)
             )
        b7 <- createBlock[Task](
               Seq(b6.blockHash),
               v2,
               bonds,
               Map(v2 -> b6.blockHash)
             )
        dag                    <- dagStorage.getRepresentation
        committeeApproximation = List(v0, v1, v2)
        levelZeroMsgs <- finalityDetectorEffect.levelZeroMsgs(
                          dag,
                          b1.blockHash,
                          committeeApproximation
                        )
        lowestLevelZeroMsgs = committeeApproximation
          .flatMap(v => levelZeroMsgs(v).lastOption)
        _ = lowestLevelZeroMsgs.map(_.blockHash) shouldBe Seq(
          b1.blockHash,
          b3.blockHash,
          b6.blockHash
        )
        sweepResult <- finalityDetectorEffect.sweep(
                        dag,
                        committeeApproximation.toSet,
                        levelZeroMsgs,
                        3,
                        3,
                        bonds.map(bond => (bond.validatorPublicKey, bond.stake)).toMap
                      )
        (blockLevelTags, validatorLevel) = sweepResult
        _                                = validatorLevel.contains(v3) shouldBe false
        b7LevelTags                      = blockLevelTags(b7.blockHash)
        // b7 doesn't have direct zero-level justification b3, but its non-level-zero indirect justification b5 has seen it
        _ = b7LevelTags.blockLevel shouldBe 1
        _ = b7LevelTags.highestLevelBySeenBlocks(v1) shouldBe 0
      } yield ()
  }

  // See [[/docs/casper/images/no_finalizable_block_mistake_with_no_disagreement_check.png]]
  it should "detect possible disagreements appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      val v1     = generateValidator("Validator One")
      val v2     = generateValidator("Validator Two")
      val v3     = generateValidator("Validator Three")
      val v1Bond = Bond(v1, 25)
      val v2Bond = Bond(v2, 20)
      val v3Bond = Bond(v3, 15)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)

      implicit val finalityDetectorEffect = new FinalityDetectorBySingleSweepImpl[Task]
      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
        b2 <- createBlock[Task](
               Seq(genesis.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
             )
        b3 <- createBlock[Task](
               Seq(genesis.blockHash),
               v1,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
             )
        b4 <- createBlock[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash, v3 -> b2.blockHash)
             )
        b5 <- createBlock[Task](
               Seq(b3.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> genesis.blockHash)
             )
        b6 <- createBlock[Task](
               Seq(b4.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> b4.blockHash)
             )
        b7 <- createBlock[Task](
               Seq(b5.blockHash),
               v3,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
             )
        b8 <- createBlock[Task](
               Seq(b6.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
             )

        dag <- dagStorage.getRepresentation

        genesisFaultTolerance <- FinalityDetector[Task]
                                  .normalizedFaultTolerance(dag, genesis.blockHash)
        _                = assert(genesisFaultTolerance === 0.5f +- 0.01f)
        b2FaultTolerance <- FinalityDetector[Task].normalizedFaultTolerance(dag, b2.blockHash)
        _                = assert(b2FaultTolerance === 0f +- 0.01f)
        b3FaultTolerance <- FinalityDetector[Task].normalizedFaultTolerance(dag, b3.blockHash)
        _                = assert(b3FaultTolerance === 0f +- 0.01f)
        b4FaultTolerance <- FinalityDetector[Task].normalizedFaultTolerance(dag, b4.blockHash)
        result           = assert(b4FaultTolerance === 0f +- 0.01f)
      } yield result
  }
}
