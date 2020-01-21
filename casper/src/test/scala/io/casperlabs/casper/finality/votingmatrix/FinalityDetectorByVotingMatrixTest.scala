package io.casperlabs.casper.finality.votingmatrix

import cats.effect.Sync
import cats.implicits._
import cats.mtl.FunctorRaise
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.finality.CommitteeWithConsensusValue
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.{validation, CasperState, InvalidBlock}
import io.casperlabs.models.Message
import io.casperlabs.shared.LogStub
import io.casperlabs.shared.{Cell, Log, Time}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.IndexedDagStorage
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

@silent("is never used")
class FinalityDetectorByVotingMatrixTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with StorageFixture {

  behavior of "Finality Detector of Voting Matrix"

  implicit val logEff = LogStub[Task]()
  implicit val raiseValidateErr: FunctorRaise[Task, InvalidBlock] =
    validation.raiseValidateErrorThroughApplicativeError[Task]

  it should "detect ballots finalizing a block" in withStorage {
    implicit blockStore => implicit dagStorage => implicit deployStorage =>
      _ =>
        /* The DAG looks like:
         *
         *
         *
         *
         *     Ballot
         *       \
         *       b1
         *      /
         *    a1
         *      \
         *      genesis
         */

        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v1Bond = Bond(v1, 10)
        val v2Bond = Bond(v2, 10)
        val bonds  = Seq(v1Bond, v2Bond)

        for {
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- dagStorage.getRepresentation
          implicit0(detector: FinalityDetectorVotingMatrix[Task]) <- FinalityDetectorVotingMatrix
                                                                      .of[Task](
                                                                        dag,
                                                                        genesis.blockHash,
                                                                        rFTT = 0.1
                                                                      )
          (a1, c1) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(genesis.blockHash),
                       genesis.blockHash,
                       v1,
                       bonds,
                       HashMap(v1 -> genesis.blockHash),
                       messageType = Block.MessageType.BLOCK
                     )
          _ = c1 shouldBe None
          (b1, c2) <- createBlockAndUpdateFinalityDetector[Task](
                       Seq(a1.blockHash),
                       genesis.blockHash,
                       v2,
                       bonds,
                       HashMap(v1 -> a1.blockHash),
                       messageType = Block.MessageType.BLOCK
                     )
          _ = c2 shouldBe None
          (ballot, c3) <- createBlockAndUpdateFinalityDetector[Task](
                           Seq(b1.blockHash),
                           genesis.blockHash,
                           v1,
                           bonds,
                           HashMap(v1 -> b1.blockHash),
                           messageType = Block.MessageType.BALLOT
                         )
          _ = c3 shouldBe Some(CommitteeWithConsensusValue(Set(v1, v2), 20, a1.blockHash))
        } yield ()
  }

  it should "detect finality as appropriate" in withStorage {
    implicit blockStore => implicit dagStorage => implicit deployStorage =>
      _ =>
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
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- dagStorage.getRepresentation
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
    implicit blockStore => implicit dagStorage => implicit deployStorage =>
      _ =>
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
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- dagStorage.getRepresentation
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
    implicit blockStore => implicit dagStorage => implicit deployStorage =>
      _ =>
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
          implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                              CasperState()
                                                            )
          genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- dagStorage.getRepresentation
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

  // See [[casper/src/test/resources/casper/finalityDetectorWithEquivocations.png]]
  it should "exclude the weight of validator who have been detected equivocating when searching for the committee" in withStorage {
    implicit blockStore => implicit blockDagStorage => _ => _ =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)

      for {
        implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                            CasperState()
                                                          )
        genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
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
        // b4 and b2 are both created by v2 but don't cite each other
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b1.blockHash, genesis.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v1 -> b1.blockHash),
                     ByteString.copyFromUtf8(scala.util.Random.nextString(64))
                   )
        _ = c4 shouldBe None
        // so v2 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v2))
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v2 -> b2.blockHash)
                   )
        _ = c3 shouldBe None
        (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b3.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     HashMap(v1 -> b1.blockHash, v3 -> b3.blockHash)
                   )
        // Though v2 also votes for b1, it has been detected equivocating, so the committee doesn't include v2 or count its weight
        result = c5 shouldBe Some(CommitteeWithConsensusValue(Set(v1, v3), 20, b1.blockHash))
      } yield result
  }

  // See [[casper/src/test/resources/casper/equivocatingBlockGetFinalized.png]]
  it should "finalize equivocator's block when enough honest validators votes for it" in withStorage {
    implicit blockStore => implicit blockDagStorage => _ => _ =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)

      for {
        implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                            CasperState()
                                                          )
        genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
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
        // b1 and b3 are both created by v1 but don't cite each other
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds
                   )
        _ = c3 shouldBe None
        // so v1 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v1))
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v2 -> b2.blockHash)
                   )
        _ = c4 shouldBe None
        (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b4.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v2 -> b2.blockHash, v3 -> b4.blockHash)
                   )
        // After creating b5, v2 knows v3 and himself vote for b1, and v3 knows v2 and
        // himself vote for b1, so v2 and v3 construct a committee.
        // So even b1 was created by v1 who equivocated, it gets finalized as having enough honest supporters
        result = c5 shouldBe Some(CommitteeWithConsensusValue(Set(v2, v3), 20, b1.blockHash))
      } yield result
  }

  // See [[casper/src/test/resources/casper/equivocatingBlockCantGetFinalized.png]]
  it should "not finalize equivocator's blocks, no matter how many votes equivocating validators cast" in withStorage {
    implicit blockStore => implicit blockDagStorage => _ => _ =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
      val v1Bond = Bond(v1, 10)
      val v2Bond = Bond(v2, 10)
      val v3Bond = Bond(v3, 10)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)
      for {
        implicit0(casperState: Cell[Task, CasperState]) <- Cell.mvarCell[Task, CasperState](
                                                            CasperState()
                                                          )
        genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
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
        // b1 and b3 are both created by v1 but don't cite each other
        (b3, c3) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds
                   )
        _ = c3 shouldBe None
        // so v1 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v1))
        (b4, c4) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(genesis.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v3 -> genesis.blockHash)
                   )
        _ = c4 shouldBe None
        // b4 and b5 are both created by v3 but don't cite each other
        (b5, c5) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b2.blockHash),
                     genesis.blockHash,
                     v3,
                     bonds,
                     HashMap(v2 -> b2.blockHash)
                   )
        _ = c5 shouldBe None
        // so v3 can be detected equivocating
        _ <- dag.getEquivocators.map(_ shouldBe Set(v1, v3))
        (b6, c6) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b5.blockHash),
                     genesis.blockHash,
                     v2,
                     bonds,
                     HashMap(v3 -> b5.blockHash)
                   )
        _ = c6 shouldBe None
        (b7, c7) <- createBlockAndUpdateFinalityDetector[Task](
                     Seq(b5.blockHash),
                     genesis.blockHash,
                     v1,
                     bonds,
                     HashMap(v2 -> b6.blockHash)
                   )
        // After creating b7, all validators know they all vote for v1, but b1 still can not get finalized, because v1 and v3 equivocated
        result = c7 shouldBe None
      } yield result
  }

  def createBlockAndUpdateFinalityDetector[F[_]: Sync: Time: Log: BlockStorage: IndexedDagStorage: FinalityDetectorVotingMatrix: FunctorRaise[
    *[_],
    InvalidBlock
  ]](
      parentsHashList: Seq[BlockHash],
      keyBlockHash: BlockHash,
      creator: Validator,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      postStateHash: ByteString = ByteString.copyFromUtf8(scala.util.Random.nextString(64)),
      messageType: Block.MessageType = Block.MessageType.BLOCK
  )(
      implicit casperState: Cell[F, CasperState]
  ): F[(Block, Option[CommitteeWithConsensusValue])] =
    for {
      block <- createMessage[F](
                parentsHashList,
                keyBlockHash,
                creator,
                bonds,
                justifications,
                postStateHash = postStateHash,
                messageType = messageType
              )
      dag     <- IndexedDagStorage[F].getRepresentation
      message <- Sync[F].fromTry(Message.fromBlock(block))
      // EquivocationDetector works before adding block to DAG
      _ <- Sync[F].attempt(
            EquivocationDetector
              .checkEquivocationWithUpdate(dag, message)
          )
      _   <- BlockStorage[F].put(block.blockHash, block, Map.empty)
      msg <- Sync[F].fromTry(Message.fromBlock(block))
      finalizedBlockOpt <- FinalityDetectorVotingMatrix[F].onNewMessageAddedToTheBlockDag(
                            dag,
                            msg,
                            keyBlockHash
                          )
    } yield block -> finalizedBlockOpt
}
