package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.casper.helper.{BlockDagStorageFixture, BlockGenerator}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.SafetyOracle.Committee
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import monix.eval.Task

import scala.collection.immutable.{HashMap, HashSet}

class CliqueOracleTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {

  behavior of "Clique Oracle"

  implicit val logEff = new LogStub[Task]

  it should "detect finality as appropriate" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
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

        implicit val cliqueOracleEffect = new SafetyOracleInstancesImpl[Task]

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
          dag           <- blockDagStorage.getRepresentation
          levelZeroMsgs <- cliqueOracleEffect.levelZeroMsgs(dag, b1.blockHash, List(v1, v2))
          lowestLevelZeroMsgs = levelZeroMsgs.flatMap {
            case (bh, msgs) => msgs.lastOption.map(_.blockHash)
          }.toSet
          _    = lowestLevelZeroMsgs shouldBe Set(b2.blockHash, b3.blockHash)
          jDag = cliqueOracleEffect.constructJDagFromLevelZeroMsgs(levelZeroMsgs)
          _    = jDag.parentToChildAdjacencyList(b2.blockHash) shouldBe Set(b4.blockHash)
          _    = jDag.parentToChildAdjacencyList(b3.blockHash) shouldBe Set(b4.blockHash, b5.blockHash)
          _    = jDag.parentToChildAdjacencyList(b4.blockHash) shouldBe Set(b6.blockHash, b7.blockHash)
          sweepResult <- cliqueOracleEffect.sweep(
                          dag,
                          jDag,
                          Set(v1, v2),
                          levelZeroMsgs,
                          2,
                          HashMap(v1 -> 1, v2 -> 1)
                        )
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
          committee                      <- SafetyOracle[Task].findBestCommittee(dag, b1.blockHash)
          _                              = committee shouldBe Some(Committee(Set(v1, v2), 2))
        } yield result
  }

  // See [[/docs/casper/images/no_finalizable_block_mistake_with_no_disagreement_check.png]]
  it should "detect possible disagreements appropriately" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v1     = generateValidator("Validator One")
      val v2     = generateValidator("Validator Two")
      val v3     = generateValidator("Validator Three")
      val v1Bond = Bond(v1, 25)
      val v2Bond = Bond(v2, 20)
      val v3Bond = Bond(v3, 15)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)

      implicit val cliqueOracleEffect = new SafetyOracleInstancesImpl[Task]
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

        dag <- blockDagStorage.getRepresentation

        genesisFaultTolerance <- SafetyOracle[Task].normalizedFaultTolerance(dag, genesis.blockHash)
        _                     = assert(genesisFaultTolerance === 1f +- 0.01f)
        b2FaultTolerance      <- SafetyOracle[Task].normalizedFaultTolerance(dag, b2.blockHash)
        _                     = assert(b2FaultTolerance === -1f / 6 +- 0.01f)
        b3FaultTolerance      <- SafetyOracle[Task].normalizedFaultTolerance(dag, b3.blockHash)
        _                     = assert(b3FaultTolerance === -1f +- 0.01f)
        b4FaultTolerance      <- SafetyOracle[Task].normalizedFaultTolerance(dag, b4.blockHash)
        result                = assert(b4FaultTolerance === -1f / 6 +- 0.01f)
      } yield result
  }
}
