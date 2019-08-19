package io.casperlabs.casper.finality

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.finality.FinalityDetector.CommitteeWithConsensusValue
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
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
         *
         *        b6
         *      / |
         *   b4   b5
         *   |  \ |
         *   b2  b3
         *    \ /
         *    b1
         *      \
         *      genesis
         */
        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v1Bond = Bond(v1, 2)
        val v2Bond = Bond(v2, 1)
        val bonds  = Seq(v1Bond, v2Bond)

        implicit val votingMatrix: VotingMatrix[Task] =
          VotingMatrixImpl.empty[Task].unsafeRunSync(monix.execution.Scheduler.Implicits.global)

        val finalityDetectorVotingMatrix = new FinalityDetectorVotingMatrix[Task](rFTT = 0)

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- blockDagStorage.getRepresentation
          _       <- finalityDetectorVotingMatrix.rebuildFromLatestFinalizedBlock(dag, genesis.blockHash)
          b1 <- createBlockAndUpdateFinalityDetector[Task](
                 finalityDetectorVotingMatrix,
                 Seq(genesis.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 HashMap(v1 -> genesis.blockHash)
               )
          b2 <- createBlockAndUpdateFinalityDetector[Task](
                 finalityDetectorVotingMatrix,
                 Seq(b1.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds
               )
          b3 <- createBlockAndUpdateFinalityDetector[Task](
                 finalityDetectorVotingMatrix,
                 Seq(b1.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds
               )
          b4 <- createBlockAndUpdateFinalityDetector[Task](
                 finalityDetectorVotingMatrix,
                 Seq(b2.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 HashMap(v1 -> b2.blockHash, v2 -> b3.blockHash)
               )
          b5 <- createBlockAndUpdateFinalityDetector[Task](
                 finalityDetectorVotingMatrix,
                 Seq(b3.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds,
                 HashMap(v2 -> b3.blockHash)
               )
          b6 <- createBlockAndUpdateFinalityDetector[Task](
                 finalityDetectorVotingMatrix,
                 Seq(b5.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds,
                 HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash)
               )

          finalDag  <- blockDagStorage.getRepresentation
          committee <- finalityDetectorVotingMatrix.findCommittee(finalDag)
          result = committee shouldBe Some(
            CommitteeWithConsensusValue(Set(v1, v2), 3, b1.blockHash)
          )
        } yield result
  }
}
