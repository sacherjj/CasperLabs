package io.casperlabs.casper.finality

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.finality.FinalityDetector.CommitteeWithConsensusValue
import io.casperlabs.casper.finality.FinalityDetectorVotingMatrix._votingMatrixS
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
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
         *
         *
         *   b4
         *   |  \
         *   b3  b2
         *    \ /
         *    b1
         *      \
         *      genesis
         */
        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v1Bond = Bond(v1, 20)
        val v2Bond = Bond(v2, 10)
        val bonds  = Seq(v1Bond, v2Bond)

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- blockDagStorage.getRepresentation
          implicit0(votingMatrixS: _votingMatrixS[Task]) <- FinalityDetectorVotingMatrix
                                                             .of[Task](dag, genesis.blockHash)
          implicit0(detector: FinalityDetectorVotingMatrix[Task]) = new FinalityDetectorVotingMatrix[
            Task
          ](rFTT = 0)
          (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(genesis.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> genesis.blockHash)
                     )
          _ = c1 shouldBe Some(CommitteeWithConsensusValue(Set(v1), 20, b1.blockHash))
          (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b1.blockHash),
                       b1.blockHash,
                       v2,
                       bonds,
                       HashMap(v1 -> b1.blockHash)
                     )
          _ = c2 shouldBe None
          (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b1.blockHash),
                       b1.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b1.blockHash)
                     )
          _ = c3 shouldBe Some(CommitteeWithConsensusValue(Set(v1), 20, b3.blockHash))
          (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b3.blockHash),
                       b3.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash)
                     )
          result = c4 shouldBe Some(CommitteeWithConsensusValue(Set(v1), 20, b4.blockHash))
        } yield result
  }
}
