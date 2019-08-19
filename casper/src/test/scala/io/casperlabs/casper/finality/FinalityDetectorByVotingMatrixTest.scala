package io.casperlabs.casper.finality

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.finality.FinalityDetector.CommitteeWithConsensusValue
import io.casperlabs.casper.finality.VotingMatrixImpl._votingMatrix
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

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- blockDagStorage.getRepresentation
          implicit0(votingMatrixState: _votingMatrix[Task]) <- VotingMatrixImpl.create(
                                                                dag,
                                                                genesis.blockHash
                                                              )
          finalityDetectorVotingMatrix = new FinalityDetectorVotingMatrix[Task](rFTT = 0)
          (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                       finalityDetectorVotingMatrix,
                       Seq(genesis.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> genesis.blockHash)
                     )
          _ = println(s"C1: $c1")
          (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                       finalityDetectorVotingMatrix,
                       Seq(b1.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds
                     )
          _ = println(s"C2: $c2")
          (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                       finalityDetectorVotingMatrix,
                       Seq(b1.blockHash),
                       genesis.blockHash,
                       v2,
                       bonds
                     )
          _ = println(s"C3: $c3")
          (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                       finalityDetectorVotingMatrix,
                       Seq(b2.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b2.blockHash, v2 -> b3.blockHash)
                     )
          _ = println(s"C4: $c4")
          (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                       finalityDetectorVotingMatrix,
                       Seq(b3.blockHash),
                       genesis.blockHash,
                       v2,
                       bonds,
                       HashMap(v2 -> b3.blockHash)
                     )
          _ = println(s"C5: $c5")
          (b6, committee) <- createBlockAndUpdateFinalityDetector[Task](
                              finalityDetectorVotingMatrix,
                              Seq(b5.blockHash),
                              genesis.blockHash,
                              v2,
                              bonds,
                              HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash)
                            )

          result = committee shouldBe Some(
            CommitteeWithConsensusValue(Set(v1, v2), 3, b1.blockHash)
          )
        } yield result
  }
}
