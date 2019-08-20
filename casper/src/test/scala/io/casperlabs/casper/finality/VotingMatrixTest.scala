package io.casperlabs.casper.finality

import cats.mtl.MonadState
import cats.Monad
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockMetadata, BlockStorage, IndexedDagStorage}
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.finality.FinalityDetectorVotingMatrix._votingMatrixS
import io.casperlabs.casper.finality.VotingMatrixImpl.VotingMatrixState
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.finality.FinalityDetector.CommitteeWithConsensusValue
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
import monix.eval.Task
import org.scalatest.{Assertion, FlatSpec, Matchers}

import scala.collection.immutable.{HashMap, Map}
@silent("is never used")
class VotingMatrixTest extends FlatSpec with Matchers with BlockGenerator with DagStorageFixture {

  behavior of "Voting Matrix"

  implicit val logEff = new LogStub[Task]

  def checkMatrix(
      expect: Map[Validator, Map[Validator, Long]]
  )(implicit _votingMatrixS: _votingMatrixS[Task]): Task[Assertion] = {

    def fromMapTo2DArray(
        validators: IndexedSeq[Validator],
        mapOfMap: Map[Validator, Map[Validator, Long]]
    ): Array[Array[Long]] =
      validators
        .map(
          rowV =>
            validators
              .map(
                columnV => mapOfMap.get(rowV).flatMap(_.get(columnV)).getOrElse(0L)
              )
              .toArray
        )
        .toArray

    for {
      votingMatrixState <- MonadState[Task, VotingMatrixState].get
      result = votingMatrixState.votingMatrix shouldBe fromMapTo2DArray(
        votingMatrixState.validators,
        expect
      )
    } yield result
  }

  def checkFirstLevelZeroVote(
      expect: Map[Validator, Option[(BlockHash, Long)]]
  )(implicit _votingMatrixS: _votingMatrixS[Task]): Task[Assertion] =
    for {
      votingMatrixState <- MonadState[Task, VotingMatrixState].get
      result = votingMatrixState.firstLevelZeroVotes shouldBe (VotingMatrixImpl.fromMapToArray(
        votingMatrixState.validatorToIdx,
        expect
      ))
    } yield result

  it should "detect finality as appropriate" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        /*
         * The Dag looks like
         *
         *        b5
         *           \
         *             b4
         *           // |
         *        b3    |
         *        || \  |
         *        b1   b2
         *         \   /
         *         genesis
         */
        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v1Bond = Bond(v1, 10)
        val v2Bond = Bond(v2, 10)
        val bonds  = Seq(v1Bond, v2Bond)
        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- blockDagStorage.getRepresentation
          implicit0(votingMatrixS: _votingMatrixS[Task]) <- FinalityDetectorVotingMatrix
                                                             .of[Task](dag, genesis.blockHash)
          votingMatrixState <- MonadState[Task, VotingMatrixState].get
          _                 = votingMatrixState.votingMatrix shouldBe (Array.fill(2, 2)(0))
          _                 = votingMatrixState.firstLevelZeroVotes shouldBe (Array.fill(2)(None))
          _                 = votingMatrixState.weightMap shouldBe (Map(v1 -> 10, v2 -> 10))
          b1 <- createAndUpdateVotingMatrix[Task](
                 Seq(genesis.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds
               )

          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b1.getHeader.rank, v2 -> 0),
                  v2 -> Map(v1 -> 0, v2                 -> 0)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> None
                )
              )
          committee <- VotingMatrix.checkForCommittee[Task](0.1)
          _         = committee shouldBe None
          b2 <- createAndUpdateVotingMatrix[Task](
                 Seq(genesis.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b1.getHeader.rank, v2 -> 0),
                  v2 -> Map(v1 -> 0, v2                 -> b2.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b2.blockHash, b2.getHeader.rank))
                )
              )
          committee <- VotingMatrix.checkForCommittee[Task](0.1)
          _         = committee shouldBe None
          b3 <- createAndUpdateVotingMatrix[Task](
                 Seq(b1.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 Map(v1 -> b1.blockHash, v2 -> b2.blockHash)
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b3.getHeader.rank, v2 -> b2.getHeader.rank),
                  v2 -> Map(v1 -> 0, v2                 -> b2.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b2.blockHash, b2.getHeader.rank))
                )
              )
          committee <- VotingMatrix.checkForCommittee[Task](0.1)
          _         = committee shouldBe None
          b4 <- createAndUpdateVotingMatrix[Task](
                 Seq(b3.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds,
                 Map(v1 -> b3.blockHash, v2 -> b2.blockHash)
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b3.getHeader.rank, v2 -> b2.getHeader.rank),
                  v2 -> Map(v1 -> b3.getHeader.rank, v2 -> b4.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b1.blockHash, b4.getHeader.rank))
                )
              )

          committee <- VotingMatrix.checkForCommittee[Task](0.01)
          _         = committee shouldBe None

          b5 <- createAndUpdateVotingMatrix[Task](
                 Seq(b4.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 Map(v1 -> b3.blockHash, v2 -> b4.blockHash)
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b5.getHeader.rank, v2 -> b4.getHeader.rank),
                  v2 -> Map(v1 -> b3.getHeader.rank, v2 -> b4.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b1.blockHash, b4.getHeader.rank))
                )
              )

          committee <- VotingMatrix.checkForCommittee[Task](0.1)
          _         = committee shouldBe Some(CommitteeWithConsensusValue(Set(v1, v2), 20, b1.blockHash))

          committee <- VotingMatrix.checkForCommittee[Task](0.4)
          result = committee shouldBe Some(
            CommitteeWithConsensusValue(Set(v1, v2), 20, b1.blockHash)
          )

        } yield result
  }

}
