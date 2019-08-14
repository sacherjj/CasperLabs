package io.casperlabs.casper.util

import com.google.protobuf.ByteString
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.storage._
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

class CasperUtilTest extends FlatSpec with Matchers with BlockGenerator with DagStorageFixture {

  implicit val logEff                  = new LogStub[Task]()
  implicit val casperSmartContractsApi = ExecutionEngineServiceStub.noOpApi[Task]()

  "isInMainChain" should "classify appropriately" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      for {
        genesis <- createBlock[Task](Seq())
        b2      <- createBlock[Task](Seq(genesis.blockHash))
        b3      <- createBlock[Task](Seq(b2.blockHash))

        dag <- dagStorage.getRepresentation

        _      <- isInMainChain(dag, BlockMetadata.fromBlock(genesis), b3.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b2), b3.blockHash) shouldBeF true
        _      <- isInMainChain(dag, BlockMetadata.fromBlock(b3), b2.blockHash) shouldBeF false
        result <- isInMainChain(dag, BlockMetadata.fromBlock(b3), genesis.blockHash) shouldBeF false
      } yield result
  }

  "isInMainChain" should "classify diamond DAGs appropriately" in withStorage {
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
        result <- isInMainChain(dag, BlockMetadata.fromBlock(b3), b4.blockHash) shouldBeF false
      } yield result
  }

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "isInMainChain" should "classify complicated chains appropriately" in withStorage {
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
        result <- isInMainChain(dag, BlockMetadata.fromBlock(b4), b2.blockHash) shouldBeF false
      } yield result
  }
}
