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
import io.casperlabs.casper.helper.BlockGenerator
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
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
    implicit blockStorage => implicit dagStorage =>
      for {
        genesis <- createBlock[Task](Seq())
        b2      <- createBlock[Task](Seq(genesis.blockHash))
        b3      <- createBlock[Task](Seq(b2.blockHash))

        dag <- dagStorage.getRepresentation

        _      <- isInMainChain(dag, BlockMetadata.fromBlock(genesis), b3.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b2), b3.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b3), b2.blockHash) shouldBeF false
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b3), genesis.blockHash) shouldBeF false
        result <- votedBranch(dag, genesis.blockHash, b3.blockHash) shouldBeF Some(b2.blockHash)
      } yield result
  }

  "isInMainChain and votedBranch" should "classify diamond DAGs appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage =>
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

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "isInMainChain and votedBranch" should "classify complicated chains appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      val v1 = generateValidator("Validator One")
      val v2 = generateValidator("Validator Two")

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
        result <- votedBranch(dag, b5.blockHash, b8.blockHash) shouldBeF None
      } yield result
  }

}
