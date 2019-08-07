package io.casperlabs.casper.api

import cats.Id
import cats.effect.Sync
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStorage, IndexedDagStorage}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.{genesis, _}
import io.casperlabs.casper.helper._
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

// See [[/docs/casper/images/no_finalizable_block_mistake_with_no_disagreement_check.png]]
@silent("deprecated")
class BlocksResponseAPITest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with DagStorageFixture {

  val v1     = generateValidator("Validator One")
  val v2     = generateValidator("Validator Two")
  val v3     = generateValidator("Validator Three")
  val v1Bond = Bond(v1, 25)
  val v2Bond = Bond(v2, 20)
  val v3Bond = Bond(v3, 15)
  val bonds  = Seq(v1Bond, v2Bond, v3Bond)

  "showMainChain" should "return only blocks in the main chain" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
        b2 <- createBlock[Task](
               Seq(genesis.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
             )
        b3 <- createBlock[Task](
               Seq(genesis.blockHash),
               v1,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
             )
        b4 <- createBlock[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash, v3 -> b2.blockHash)
             )
        b5 <- createBlock[Task](
               Seq(b3.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> genesis.blockHash)
             )
        b6 <- createBlock[Task](
               Seq(b4.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> b4.blockHash)
             )
        _ <- createBlock[Task](
              Seq(b5.blockHash),
              v3,
              bonds,
              HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
            )
        _ <- createBlock[Task](
              Seq(b6.blockHash),
              v2,
              bonds,
              HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
            )
        dag  <- dagStorage.getRepresentation
        tips <- Estimator.tips[Task](dag, genesis.blockHash)
        casperEffect <- NoOpsCasperEffect[Task](
                         HashMap.empty[BlockHash, BlockMsgWithTransform],
                         tips
                       )
        logEff                 = new LogStub[Task]
        casperRef              <- MultiParentCasperRef.of[Task]
        _                      <- casperRef.set(casperEffect)
        finalityDetectorEffect = new FinalityDetectorInstancesImpl[Task]()(Sync[Task], logEff)
        blocksResponse <- BlockAPI.showMainChain[Task](Int.MaxValue)(
                           Sync[Task],
                           casperRef,
                           logEff,
                           finalityDetectorEffect,
                           blockStorage
                         )
      } yield blocksResponse.length should be(5)
  }

  "showBlocks" should "return all blocks" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
        b2 <- createBlock[Task](
               Seq(genesis.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
             )
        b3 <- createBlock[Task](
               Seq(genesis.blockHash),
               v1,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
             )
        b4 <- createBlock[Task](
               Seq(b2.blockHash),
               v3,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash, v3 -> b2.blockHash)
             )
        b5 <- createBlock[Task](
               Seq(b3.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> genesis.blockHash)
             )
        b6 <- createBlock[Task](
               Seq(b4.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> b4.blockHash)
             )
        _ <- createBlock[Task](
              Seq(b5.blockHash),
              v3,
              bonds,
              HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
            )
        _ <- createBlock[Task](
              Seq(b6.blockHash),
              v2,
              bonds,
              HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
            )
        dag  <- dagStorage.getRepresentation
        tips <- Estimator.tips[Task](dag, genesis.blockHash)
        casperEffect <- NoOpsCasperEffect[Task](
                         HashMap.empty[BlockHash, BlockMsgWithTransform],
                         tips
                       )
        logEff                 = new LogStub[Task]
        casperRef              <- MultiParentCasperRef.of[Task]
        _                      <- casperRef.set(casperEffect)
        finalityDetectorEffect = new FinalityDetectorInstancesImpl[Task]()(Sync[Task], logEff)
        blocksResponse <- BlockAPI.showBlocks[Task](Int.MaxValue)(
                           Sync[Task],
                           casperRef,
                           logEff,
                           finalityDetectorEffect,
                           blockStorage
                         )
      } yield blocksResponse.length should be(8) // TODO: Switch to 4 when we implement block height correctly
  }

  it should "return until depth" in withStorage { implicit blockStorage => implicit dagStorage =>
    for {
      genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
      b2 <- createBlock[Task](
             Seq(genesis.blockHash),
             v2,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
           )
      b3 <- createBlock[Task](
             Seq(genesis.blockHash),
             v1,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
           )
      b4 <- createBlock[Task](
             Seq(b2.blockHash),
             v3,
             bonds,
             HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash, v3 -> b2.blockHash)
           )
      b5 <- createBlock[Task](
             Seq(b3.blockHash),
             v2,
             bonds,
             HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> genesis.blockHash)
           )
      b6 <- createBlock[Task](
             Seq(b4.blockHash),
             v1,
             bonds,
             HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash, v3 -> b4.blockHash)
           )
      _ <- createBlock[Task](
            Seq(b5.blockHash),
            v3,
            bonds,
            HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
          )
      _ <- createBlock[Task](
            Seq(b6.blockHash),
            v2,
            bonds,
            HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
          )
      dag  <- dagStorage.getRepresentation
      tips <- Estimator.tips[Task](dag, genesis.blockHash)
      casperEffect <- NoOpsCasperEffect[Task](
                       HashMap.empty[BlockHash, BlockMsgWithTransform],
                       tips
                     )
      logEff                 = new LogStub[Task]
      casperRef              <- MultiParentCasperRef.of[Task]
      _                      <- casperRef.set(casperEffect)
      finalityDetectorEffect = new FinalityDetectorInstancesImpl[Task]()(Sync[Task], logEff)
      blocksResponse <- BlockAPI.showBlocks[Task](2)(
                         Sync[Task],
                         casperRef,
                         logEff,
                         finalityDetectorEffect,
                         blockStorage
                       )
    } yield blocksResponse.length should be(2) // TODO: Switch to 3 when we implement block height correctly
  }
}
