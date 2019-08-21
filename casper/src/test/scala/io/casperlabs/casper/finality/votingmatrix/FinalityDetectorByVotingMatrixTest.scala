package io.casperlabs.casper.finality.votingmatrix

import cats.Monad
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStorage, IndexedDagStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.finality.CommitteeWithConsensusValue
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix._votingMatrixS
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
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
          implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- FinalityDetectorVotingMatrix
                                                                      .of[Task](
                                                                        dag,
                                                                        genesis.blockHash,
                                                                        rFTT = 0.1
                                                                      )
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

  it should "finalize blocks properly with only one validator" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        /* The DAG looks like:
         *
         *    b4
         *    |
         *    b3
         *    |
         *    b2
         *    |
         *    b1
         *      \
         *      genesis
         */
        val v1     = generateValidator("V1")
        val v1Bond = Bond(v1, 10)
        val bonds  = Seq(v1Bond)
        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- blockDagStorage.getRepresentation
          implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- FinalityDetectorVotingMatrix
                                                                      .of[Task](
                                                                        dag,
                                                                        genesis.blockHash,
                                                                        rFTT = 0.1
                                                                      )
          (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(genesis.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds
                     )

          _ = c1 shouldBe Some(CommitteeWithConsensusValue(Set(v1), 10, b1.blockHash))
          (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b1.blockHash),
                       b1.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b1.blockHash)
                     )
          _ = c2 shouldBe Some(CommitteeWithConsensusValue(Set(v1), 10, b2.blockHash))
          (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b2.blockHash),
                       b2.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b2.blockHash)
                     )
          _ = c3 shouldBe Some(CommitteeWithConsensusValue(Set(v1), 10, b3.blockHash))
          (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b3.blockHash),
                       b3.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b3.blockHash)
                     )
          result = c4 shouldBe Some(CommitteeWithConsensusValue(Set(v1), 10, b4.blockHash))
        } yield result
  }

  it should "increment last finalized block as appropriate in round robin" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        /* The DAG looks like:
         *
         *
         *    b7 ----
         *           \
         *            b6
         *           /
         *        b5
         *      /
         *    b4 ----
         *           \
         *            b3
         *          /
         *        b2
         *      /
         *    b1
         *      \
         *      genesis
         */
        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v3     = generateValidator("V3")
        val v1Bond = Bond(v1, 10)
        val v2Bond = Bond(v2, 10)
        val v3Bond = Bond(v3, 10)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)
        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- blockDagStorage.getRepresentation
          implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- FinalityDetectorVotingMatrix
                                                                      .of[Task](
                                                                        dag,
                                                                        genesis.blockHash,
                                                                        rFTT = 0.1
                                                                      )
          (b1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(genesis.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds
                     )
          _ = c1 shouldBe None
          (b2, c2) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b1.blockHash),
                       genesis.blockHash,
                       v2,
                       bonds,
                       HashMap(v1 -> b1.blockHash)
                     )
          _ = c2 shouldBe None
          (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b2.blockHash),
                       genesis.blockHash,
                       v3,
                       bonds,
                       HashMap(v1 -> b1.blockHash, v2 -> b2.blockHash)
                     )
          _ = c3 shouldBe None
          (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b3.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b1.blockHash, v2 -> b2.blockHash, v3 -> b3.blockHash)
                     )
          _ = c4 shouldBe Some(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b1.blockHash))
          (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b4.blockHash),
                       b1.blockHash,
                       v2,
                       bonds,
                       HashMap(v1 -> b4.blockHash, v2 -> b2.blockHash, v3 -> b3.blockHash)
                     )
          _ = c5 shouldBe Some(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b2.blockHash))
          (b6, c6) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b5.blockHash),
                       b2.blockHash,
                       v3,
                       bonds,
                       HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash, v3 -> b3.blockHash)
                     )
          _ = c6 shouldBe Some(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b3.blockHash))
          (b7, c7) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(b6.blockHash),
                       b3.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> b4.blockHash, v2 -> b5.blockHash, v3 -> b6.blockHash)
                     )
          result = c7 shouldBe Some(CommitteeWithConsensusValue(Set(v1, v2, v3), 30, b4.blockHash))
        } yield result
  }

  def createBlockAndUpdateFinalityDetector[F[_]: Monad: Time: BlockStorage: IndexedDagStorage: FinalityDetectorVotingMatrix](
      parentsHashList: Seq[BlockHash],
      lastFinalizedBlockHash: BlockHash,
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash]
  ): F[(Block, Option[CommitteeWithConsensusValue])] =
    for {
      block <- createBlock[F](
                parentsHashList,
                creator,
                bonds,
                justifications
              )
      dag <- IndexedDagStorage[F].getRepresentation
      finalizedBlockOpt <- FinalityDetectorVotingMatrix[F].onNewBlockAddedToTheBlockDag(
                            dag,
                            block,
                            lastFinalizedBlockHash
                          )
    } yield block -> finalizedBlockOpt
}
