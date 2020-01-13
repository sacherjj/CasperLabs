package io.casperlabs.casper.util

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.FinalityDetectorUtil
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.models.Message
import io.casperlabs.shared.LogStub
import io.casperlabs.storage.dag._
import monix.eval.Task
import org.scalatest.{Assertion, FlatSpec, Matchers}

class CasperUtilTest extends FlatSpec with Matchers with BlockGenerator with StorageFixture {

  implicit val logEff                  = LogStub[Task]()
  implicit val casperSmartContractsApi = ExecutionEngineServiceStub.noOpApi[Task]()

  /**
    * when a==b, this method doesn't work,
    * because `isInMainChain` return `true`, but votedBranch return `None`
    */
  def testVoteBranchAndIsInMainChain(
      a: Block,
      b: Block,
      result: Option[Block]
  )(implicit dag: DagRepresentation[Task]): Task[Assertion] = {
    require(a.blockHash != b.blockHash)
    (isInMainChain(dag, a.blockHash, b.blockHash) shouldBeF result.isDefined) *>
      (votedBranch(dag, a.blockHash, b.blockHash) shouldBeF result.map(_.blockHash))
  }

  "isInMainChain and votedBranch" should "classify appropriately when using the same block" in withStorage {
    implicit blockStorage => implicit dagStorage => _ => _ =>
      for {
        b      <- createAndStoreMessage[Task](Seq())
        dag    <- dagStorage.getRepresentation
        _      <- isInMainChain(dag, b.blockHash, b.blockHash) shouldBeF true
        result <- votedBranch(dag, b.blockHash, b.blockHash) shouldBeF None
      } yield result
  }

  it should "classify appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      _ =>
        /**
          * The DAG looks like:
          *
          *     b3
          *     |
          *     b2
          *     |
          *     b1
          *
          */
        for {
          genesis <- createAndStoreMessage[Task](Seq())
          b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
          b3      <- createAndStoreMessage[Task](Seq(b2.blockHash))

          implicit0(dag: DagRepresentation[Task]) <- dagStorage.getRepresentation

          _      <- testVoteBranchAndIsInMainChain(genesis, b2, Some(b2))
          _      <- testVoteBranchAndIsInMainChain(b2, genesis, None)
          _      <- testVoteBranchAndIsInMainChain(genesis, b3, Some(b2))
          _      <- testVoteBranchAndIsInMainChain(b3, genesis, None)
          _      <- testVoteBranchAndIsInMainChain(b2, b3, Some(b3))
          result <- testVoteBranchAndIsInMainChain(b3, b2, None)
        } yield result
  }

  it should "classify diamond DAGs appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      _ =>
        /**
          * The dag looks like:
          *
          *            b4
          *           // \
          *          b2  b3
          *           \  /
          *          genesis
          */
        for {
          genesis <- createAndStoreMessage[Task](Seq())
          b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
          b3      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
          b4      <- createAndStoreMessage[Task](Seq(b2.blockHash, b3.blockHash))

          implicit0(dag: DagRepresentation[Task]) <- dagStorage.getRepresentation

          _      <- testVoteBranchAndIsInMainChain(genesis, b2, Some(b2))
          _      <- testVoteBranchAndIsInMainChain(genesis, b3, Some(b3))
          _      <- testVoteBranchAndIsInMainChain(genesis, b4, Some(b2))
          _      <- testVoteBranchAndIsInMainChain(b2, b4, Some(b4))
          result <- testVoteBranchAndIsInMainChain(b3, b4, None)
        } yield result
  }

  it should "classify complicated chains appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage => _ => _ =>
      val v1 = generateValidator("V1")
      val v2 = generateValidator("V2")

      /**
        * The dag looks like:
        *
        *           b8
        *           |
        *           b7   b6
        *             \  |
        *               \|
        *           b5   b4
        *             \  |
        *              \ |
        *           b3   b2
        *             \  /
        *            genesis
        *
        *
        */
      for {
        genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY)
        b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b4      <- createAndStoreMessage[Task](Seq(b2.blockHash), v2)
        b5      <- createAndStoreMessage[Task](Seq(b2.blockHash), v1)
        b6      <- createAndStoreMessage[Task](Seq(b4.blockHash), v2)
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash), v1)
        b8      <- createAndStoreMessage[Task](Seq(b7.blockHash), v1)

        implicit0(dag: DagRepresentation[Task]) <- dagStorage.getRepresentation

        _      <- testVoteBranchAndIsInMainChain(genesis, b2, Some(b2))
        _      <- testVoteBranchAndIsInMainChain(genesis, b3, Some(b3))
        _      <- testVoteBranchAndIsInMainChain(genesis, b4, Some(b2))
        _      <- testVoteBranchAndIsInMainChain(genesis, b5, Some(b2))
        _      <- testVoteBranchAndIsInMainChain(genesis, b8, Some(b2))
        _      <- testVoteBranchAndIsInMainChain(b2, b3, None)
        _      <- testVoteBranchAndIsInMainChain(b3, b4, None)
        _      <- testVoteBranchAndIsInMainChain(b4, b5, None)
        _      <- testVoteBranchAndIsInMainChain(b5, b6, None)
        _      <- testVoteBranchAndIsInMainChain(b6, b7, None)
        _      <- testVoteBranchAndIsInMainChain(b7, b8, Some(b8))
        _      <- testVoteBranchAndIsInMainChain(b2, b6, Some(b4))
        _      <- testVoteBranchAndIsInMainChain(b2, b8, Some(b4))
        _      <- testVoteBranchAndIsInMainChain(b4, b8, Some(b7))
        _      <- testVoteBranchAndIsInMainChain(b5, b8, None)
        result <- testVoteBranchAndIsInMainChain(b4, b2, None)
      } yield result
  }

  // See [[casper/src/test/resources/casper/panoramaForEquivocatorSwimlaneIsEmpty.png]]
  "panoramaDagLevelsOfBlock" should "properly return the panorama of message B" in withStorage {
    implicit blockStorage => implicit dagStorage => _ => _ =>
      val v0         = generateValidator("V0")
      val v1         = generateValidator("V1")
      val v2         = generateValidator("V2")
      val v3         = generateValidator("V3")
      val v4         = generateValidator("V4")
      val validators = List(v0, v1, v2, v3, v4)

      val bonds = validators.map(v => Bond(v, 1))

      for {
        genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY)
        b1 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v0,
               bonds,
               Map(v0 -> genesis.blockHash)
             )
        b2 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v3,
               bonds,
               Map(v3 -> genesis.blockHash)
             )
        b3 <- createAndStoreMessage[Task](
               Seq(b1.blockHash),
               v1,
               bonds,
               Map(v0 -> b1.blockHash, v1 -> genesis.blockHash)
             )
        b4 <- createAndStoreMessage[Task](
               Seq(b1.blockHash),
               v0,
               bonds,
               Map(v0 -> b1.blockHash)
             )
        // b5 votes for b2 instead of b1
        b5 <- createAndStoreMessage[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               Map(v1 -> b3.blockHash, v3 -> b2.blockHash)
             )
        b6 <- createAndStoreMessage[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               Map(v0 -> b4.blockHash, v2 -> genesis.blockHash, v3 -> b5.blockHash)
             )
        b7 <- createAndStoreMessage[Task](
               Seq(b6.blockHash),
               v2,
               bonds,
               Map(v2 -> b6.blockHash)
             )
        dag <- dagStorage.getRepresentation

        panoramaDagLevel <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                             dag,
                             Message.fromBlock(genesis).get,
                             validators.toSet
                           )
        _ = panoramaDagLevel shouldEqual Map()

        panoramaDagLevel1 <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                              dag,
                              Message.fromBlock(b1).get,
                              validators.toSet
                            )
        _ = panoramaDagLevel1 shouldEqual Map(
          v0 -> b1.getHeader.rank
        )

        panoramaDagLevel2 <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                              dag,
                              Message.fromBlock(b3).get,
                              validators.toSet
                            )
        _ = panoramaDagLevel2 shouldEqual Map(
          v0 -> b1.getHeader.rank,
          v1 -> b3.getHeader.rank
        )

        panoramaDagLevel3 <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                              dag,
                              Message.fromBlock(b5).get,
                              validators.toSet
                            )
        _ = panoramaDagLevel3 shouldEqual Map(
          v0 -> b1.getHeader.rank,
          v1 -> b3.getHeader.rank,
          v3 -> b5.getHeader.rank
        )

        panoramaDagLevel4 <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                              dag,
                              Message.fromBlock(b6).get,
                              validators.toSet
                            )
        _ = panoramaDagLevel4 shouldEqual Map(
          v0 -> b4.getHeader.rank,
          v1 -> b3.getHeader.rank,
          v2 -> b6.getHeader.rank,
          v3 -> b5.getHeader.rank
        )

        panoramaDagLevel5 <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                              dag,
                              Message.fromBlock(b7).get,
                              validators.toSet
                            )
        _ = panoramaDagLevel5 shouldEqual Map(
          v0 -> b4.getHeader.rank,
          v1 -> b3.getHeader.rank,
          v2 -> b7.getHeader.rank,
          v3 -> b5.getHeader.rank
        )
      } yield ()
  }

  // See [[casper/src/test/resources/casper/panoramaForEquivocatorSwimlaneIsEmpty.png]]
  "panoramaM" should "properly return the panorama of message B, and when V(j)-swimlane is empty or V(j) happens to be an equivocator, puts 0 in the corresponding cell." in withStorage {
    implicit blockStorage => implicit blockDagStorage => _ => _ =>
      val v0                = generateValidator("V0")
      val v1                = generateValidator("V1")
      val v2                = generateValidator("V2")
      val v3                = generateValidator("V3")
      val v4                = generateValidator("V4")
      val validators        = List(v0, v1, v2, v3, v4)
      val validatorsToIndex = validators.zipWithIndex.toMap

      val bonds = validators.map(v => Bond(v, 1))

      for {
        genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY)
        b1 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v0,
               bonds
             )
        b2 <- createAndStoreMessage[Task](
               Seq(genesis.blockHash),
               v3,
               bonds
             )
        _ <- createAndStoreMessage[Task](
              Seq(b1.blockHash),
              v1,
              bonds,
              Map(v0 -> b1.blockHash)
            )
        b4 <- createAndStoreMessage[Task](
               Seq(b1.blockHash),
               v0,
               bonds,
               Map(v0 -> b1.blockHash)
             )
        // b5 votes for b2 instead of b1
        b5 <- createAndStoreMessage[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               Map(v3 -> b2.blockHash)
             )
        b6 <- createAndStoreMessage[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               Map(v0 -> b4.blockHash, v3 -> b5.blockHash)
             )
        b7 <- createAndStoreMessage[Task](
               Seq(b6.blockHash),
               v2,
               bonds,
               Map(v2 -> b6.blockHash)
             )
        _ <- createAndStoreMessage[Task](
              Seq(b7.blockHash),
              v1,
              bonds,
              Map(v2 -> b4.blockHash) // skip v1 last message in justifications
            )
        dag <- blockDagStorage.getRepresentation
        panoramaM <- FinalityDetectorUtil.panoramaM(
                      dag,
                      validatorsToIndex,
                      Message.fromBlock(b7).get
                    )
        _ = panoramaM.size shouldBe (validatorsToIndex.size)
        _ = panoramaM shouldBe IndexedSeq(
          b4.getHeader.rank,
          0, // V(1) happens to be an equivocator
          b7.getHeader.rank,
          b5.getHeader.rank,
          0 // V(4)-swimlane is empty
        )
      } yield ()
  }
}
