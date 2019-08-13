package io.casperlabs.casper.util

import ProtoUtil._
import com.google.protobuf.ByteString
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.catscontrib._
import cats.implicits._
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import cats.data._
import cats.effect.Bracket
import cats.implicits._
import cats.mtl.MonadState
import cats.mtl.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, BlockStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.helper.BlockGenerator
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.casper.{FinalityDetectorBySingleSweepImpl, FinalityDetectorUtil}
import monix.eval.Task
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task

import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.{HashMap, HashSet}

class CasperUtilTest extends FlatSpec with Matchers with BlockGenerator with DagStorageFixture {

  implicit val logEff                  = new LogStub[Task]()
  implicit val casperSmartContractsApi = ExecutionEngineServiceStub.noOpApi[Task]()

  "isInMainChain and votedBranch" should "classify appropriately" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
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
          genesis <- createBlock[Task](Seq())
          b2      <- createBlock[Task](Seq(genesis.blockHash))
          b3      <- createBlock[Task](Seq(b2.blockHash))

          dag <- dagStorage.getRepresentation

          _      <- isInMainChain(dag, BlockMetadata.fromBlock(genesis), b3.blockHash) shouldBeF true
          _      <- isInMainChain(dag, BlockMetadata.fromBlock(b2), b3.blockHash) shouldBeF true
          _      <- isInMainChain(dag, BlockMetadata.fromBlock(b3), b2.blockHash) shouldBeF false
          _      <- isInMainChain(dag, BlockMetadata.fromBlock(b3), genesis.blockHash) shouldBeF false
          _      <- votedBranch(dag, genesis.blockHash, b2.blockHash) shouldBeF Some(b2.blockHash)
          _      <- votedBranch(dag, b2.blockHash, b3.blockHash) shouldBeF Some(b3.blockHash)
          _      <- votedBranch(dag, b2.blockHash, b2.blockHash) shouldBeF None
          _      <- votedBranch(dag, b3.blockHash, b2.blockHash) shouldBeF None
          result <- votedBranch(dag, genesis.blockHash, b3.blockHash) shouldBeF Some(b2.blockHash)
        } yield result
  }

  "isInMainChain and votedBranch" should "classify diamond DAGs appropriately" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
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
          genesis <- createBlock[Task](Seq())
          b2      <- createBlock[Task](Seq(genesis.blockHash))
          b3      <- createBlock[Task](Seq(genesis.blockHash))
          b4      <- createBlock[Task](Seq(b2.blockHash, b3.blockHash))

          dag <- dagStorage.getRepresentation

          _      <- isInMainChain(dag, BlockMetadata.fromBlock(genesis), b2.blockHash) shouldBeF true
          _      <- isInMainChain(dag, BlockMetadata.fromBlock(genesis), b3.blockHash) shouldBeF true
          _      <- isInMainChain(dag, BlockMetadata.fromBlock(genesis), b4.blockHash) shouldBeF true
          _      <- isInMainChain(dag, BlockMetadata.fromBlock(b2), b4.blockHash) shouldBeF true
          _      <- isInMainChain(dag, BlockMetadata.fromBlock(b3), b4.blockHash) shouldBeF false
          _      <- votedBranch(dag, genesis.blockHash, b2.blockHash) shouldBeF Some(b2.blockHash)
          _      <- votedBranch(dag, genesis.blockHash, b3.blockHash) shouldBeF Some(b3.blockHash)
          _      <- votedBranch(dag, genesis.blockHash, b4.blockHash) shouldBeF Some(b2.blockHash)
          _      <- votedBranch(dag, b2.blockHash, b4.blockHash) shouldBeF Some(b4.blockHash)
          _      <- votedBranch(dag, b3.blockHash, b4.blockHash) shouldBeF None
          result <- votedBranch(dag, b2.blockHash, b3.blockHash) shouldBeF None
        } yield result
  }

  "isInMainChain and votedBranch" should "classify complicated chains appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage =>
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
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
        b2      <- createBlock[Task](Seq(genesis.blockHash), v2)
        b3      <- createBlock[Task](Seq(genesis.blockHash), v1)
        b4      <- createBlock[Task](Seq(b2.blockHash), v2)
        b5      <- createBlock[Task](Seq(b2.blockHash), v1)
        b6      <- createBlock[Task](Seq(b4.blockHash), v2)
        b7      <- createBlock[Task](Seq(b4.blockHash), v1)
        b8      <- createBlock[Task](Seq(b7.blockHash), v1)

        dag <- dagStorage.getRepresentation

        _      <- isInMainChain(dag, BlockMetadata.fromBlock(genesis), b2.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b2), b3.blockHash) shouldBeF false
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b3), b4.blockHash) shouldBeF false
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b4), b5.blockHash) shouldBeF false
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b5), b6.blockHash) shouldBeF false
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b6), b7.blockHash) shouldBeF false
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b7), b8.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b2), b6.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b2), b8.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b4), b2.blockHash) shouldBeF false
        _      <- votedBranch(dag, genesis.blockHash, b2.blockHash) shouldBeF Some(b2.blockHash)
        _      <- votedBranch(dag, genesis.blockHash, b3.blockHash) shouldBeF Some(b3.blockHash)
        _      <- votedBranch(dag, genesis.blockHash, b4.blockHash) shouldBeF Some(b2.blockHash)
        _      <- votedBranch(dag, genesis.blockHash, b5.blockHash) shouldBeF Some(b2.blockHash)
        _      <- votedBranch(dag, genesis.blockHash, b8.blockHash) shouldBeF Some(b2.blockHash)
        _      <- votedBranch(dag, b4.blockHash, b8.blockHash) shouldBeF Some(b7.blockHash)
        _      <- votedBranch(dag, b5.blockHash, b6.blockHash) shouldBeF None
        result <- votedBranch(dag, b5.blockHash, b8.blockHash) shouldBeF None
      } yield result
  }

  "panoramaDagLevelsOfBlock" should "properly return the panorama of message B" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v0 = generateValidator("V0")
      val v1 = generateValidator("V1")

      val v2         = generateValidator("V2")
      val v3         = generateValidator("V3")
      val validators = List(v0, v1, v2, v3)

      val bonds = validators.map(v => Bond(v, 1))

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
        dag <- blockDagStorage.getRepresentation
        // An extra new validator who haven't proposed blocks
        v4 = generateValidator("V4")
        panorama <- FinalityDetectorUtil.panoramaOfBlockByValidators(
                     dag,
                     BlockMetadata.fromBlock(b7),
                     validators.toSet + v4
                   )
        _ = panorama shouldEqual Map(
          v0 -> BlockMetadata.fromBlock(b4),
          v1 -> BlockMetadata.fromBlock(b3),
          v2 -> BlockMetadata.fromBlock(b7),
          v3 -> BlockMetadata.fromBlock(b5)
        )
        panoramaDagLevel <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                             dag,
                             BlockMetadata.fromBlock(b7),
                             validators.toSet + v4
                           )
        _ = panoramaDagLevel shouldEqual Map(
          v0 -> b4.getHeader.rank,
          v1 -> b3.getHeader.rank,
          v2 -> b7.getHeader.rank,
          v3 -> b5.getHeader.rank,
          v4 -> 0
        )
      } yield ()
  }
}
