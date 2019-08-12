package io.casperlabs.casper

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

@silent("is never used")
class FinalityDetectorByVotingMatrixTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with DagStorageFixture {

  behavior of "Finality Detector of Voting Matrix"

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
        val v1Bond = Bond(v1, 2)
        val v2Bond = Bond(v2, 1)
        val bonds  = Seq(v1Bond, v2Bond)

        implicit val votingMatrix: VotingMatrix[Task] =
          VotingMatrix.of[Task].unsafeRunSync(monix.execution.Scheduler.Implicits.global)

        implicit val finalityDetectorEffect = new FinalityDetectorVotingMatrix[Task]

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- blockDagStorage.getRepresentation
          _       <- finalityDetectorEffect.rebuildFromLatestFinalizedBlock(dag, genesis.blockHash)
          b1 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(genesis.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 HashMap(v1 -> genesis.blockHash)
               )
          b2 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(b1.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds
               )
          b3 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(b1.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds
               )
          b4 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(b2.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 HashMap(v1 -> b2.blockHash, v2 -> b3.blockHash)
               )
          b5 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(b3.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds,
                 HashMap(v2 -> b3.blockHash)
               )
          b6 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(b4.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash)
               )
          b7 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(b5.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds,
                 HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash)
               )
          b8 <- createBlockAndUpdateFinalityDetector[Task](
                 Seq(b6.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 HashMap(v1 -> b6.blockHash, v2 -> b7.blockHash)
               )
          finalDag <- blockDagStorage.getRepresentation
          b1NFT    <- FinalityDetector[Task].normalizedFaultTolerance(finalDag, b1.blockHash)
          result   = b1NFT shouldBe (0.5f) // so b1 get finalized
          _        <- FinalityDetector[Task].rebuildFromLatestFinalizedBlock(finalDag, b1.blockHash)
          b2NFT    <- FinalityDetector[Task].normalizedFaultTolerance(finalDag, b2.blockHash)
          b3NFT    <- FinalityDetector[Task].normalizedFaultTolerance(finalDag, b3.blockHash)
          _        = b2NFT shouldBe 1f / 6 +- 0.001f
          _        = b3NFT shouldBe 0
        } yield result
  }
}
