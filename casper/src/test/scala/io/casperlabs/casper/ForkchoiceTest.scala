package io.casperlabs.casper

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockDagStorageFixture, BlockGenerator}
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

@silent("is never used")
class ForkchoiceTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {
  "Estimator on empty latestMessages" should "return the genesis regardless of DAG" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v1     = generateValidator("Validator One")
      val v2     = generateValidator("Validator Two")
      val v1Bond = Bond(v1, 2)
      val v2Bond = Bond(v2, 3)
      val bonds  = Seq(v1Bond, v2Bond)
      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
        b2 <- createBlock[Task](
               Seq(genesis.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
             )
        b3 <- createBlock[Task](
               Seq(genesis.blockHash),
               v1,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
             )
        b4 <- createBlock[Task](
               Seq(b2.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash)
             )
        b5 <- createBlock[Task](
               Seq(b2.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash)
             )
        b6 <- createBlock[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
             )
        b7 <- createBlock[Task](
               Seq(b4.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
             )
        b8 <- createBlock[Task](
               Seq(b7.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b7.blockHash, v2 -> b4.blockHash)
             )
        dag <- blockDagStorage.getRepresentation
        forkchoice <- Estimator.tips[Task](
                       dag,
                       genesis.blockHash,
                       Map.empty[Validator, BlockHash]
                     )
      } yield forkchoice.head should be(genesis.blockHash)
  }

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "Estimator on Simple DAG" should "return the appropriate score map and forkchoice" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v1     = generateValidator("Validator One")
      val v2     = generateValidator("Validator Two")
      val v1Bond = Bond(v1, 2)
      val v2Bond = Bond(v2, 3)
      val bonds  = Seq(v1Bond, v2Bond)
      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
        b2 <- createBlock[Task](
               Seq(genesis.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
             )
        b3 <- createBlock[Task](
               Seq(genesis.blockHash),
               v1,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
             )
        b4 <- createBlock[Task](
               Seq(b2.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash)
             )
        b5 <- createBlock[Task](
               Seq(b2.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash)
             )
        b6 <- createBlock[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
             )
        b7 <- createBlock[Task](
               Seq(b4.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
             )
        b8 <- createBlock[Task](
               Seq(b7.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b7.blockHash, v2 -> b4.blockHash)
             )
        dag <- blockDagStorage.getRepresentation
        latestBlocks = HashMap[Validator, BlockHash](
          v1 -> b8.blockHash,
          v2 -> b6.blockHash
        )
        forkchoice <- Estimator.tips[Task](
                       dag,
                       genesis.blockHash,
                       latestBlocks
                     )
        _      = forkchoice.head should be(b6.blockHash)
        result = forkchoice(1) should be(b8.blockHash)
      } yield result
  }

  // See [[/docs/casper/images/no_finalizable_block_mistake_with_no_disagreement_check.png]]
  "Estimator on flipping forkchoice DAG" should "return the appropriate score map and forkchoice" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v1     = generateValidator("Validator One")
      val v2     = generateValidator("Validator Two")
      val v3     = generateValidator("Validator Three")
      val v1Bond = Bond(v1, 25)
      val v2Bond = Bond(v2, 20)
      val v3Bond = Bond(v3, 15)
      val bonds  = Seq(v1Bond, v2Bond, v3Bond)
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
        b7 <- createBlock[Task](
               Seq(b5.blockHash),
               v3,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
             )
        b8 <- createBlock[Task](
               Seq(b6.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b6.blockHash, v2 -> b5.blockHash, v3 -> b4.blockHash)
             )
        dag <- blockDagStorage.getRepresentation
        latestBlocks = HashMap[Validator, BlockHash](
          v1 -> b6.blockHash,
          v2 -> b8.blockHash,
          v3 -> b7.blockHash
        )
        forkchoice <- Estimator.tips[Task](
                       dag,
                       genesis.blockHash,
                       latestBlocks
                     )
        _      = forkchoice.head should be(b8.blockHash)
        result = forkchoice(1) should be(b7.blockHash)
      } yield result
  }

  "lmdScoring" should "propagate fixed weights on a tree" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        /* The DAG looks like:
         *
         *
         *
         *   d  e   f
         *    \ /   |
         *     a    b   c
         *      \   |   /
         *       genesis
         */

        val v1     = generateValidator("Validator One")
        val v2     = generateValidator("Validator Two")
        val v3     = generateValidator("Validator Three")
        val v1Bond = Bond(v1, 3)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 7)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          a       <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
          b       <- createBlock[Task](Seq(genesis.blockHash), v2, bonds)
          _       <- createBlock[Task](Seq(genesis.blockHash), v3, bonds)
          d       <- createBlock[Task](Seq(a.blockHash), v1, bonds)
          e       <- createBlock[Task](Seq(a.blockHash), v1, bonds)
          f       <- createBlock[Task](Seq(b.blockHash), v2, bonds)
          dag     <- blockDagStorage.getRepresentation
          latestBlocks = HashMap[Validator, BlockHash](
            v1 -> d.blockHash,
            v2 -> e.blockHash,
            v3 -> f.blockHash
          )
          scores <- Estimator.lmdScoring(dag, latestBlocks)
          _ = scores shouldEqual Map(
            genesis.blockHash -> (3 + 5 + 7),
            a.blockHash       -> (3 + 5),
            b.blockHash       -> 7,
            d.blockHash       -> 3,
            e.blockHash       -> 5,
            f.blockHash       -> 7
          )
        } yield ()
  }

  it should "propagate fixed weights on a DAG" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        /* The DAG looks like:
         *
         *
         *        i
         *        |
         *        g   h
         *       | \ /  \
         *       d  e   f
         *      / \/   /
         *     a  b   c
         *      \ |  /
         *       genesis
         */
        val v1     = generateValidator("Validator One")
        val v2     = generateValidator("Validator Two")
        val v3     = generateValidator("Validator Three")
        val v1Bond = Bond(v1, 3)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 7)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          a       <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
          b       <- createBlock[Task](Seq(genesis.blockHash), v2, bonds)
          c       <- createBlock[Task](Seq(genesis.blockHash), v3, bonds)
          d       <- createBlock[Task](Seq(a.blockHash, b.blockHash), v1, bonds)
          e       <- createBlock[Task](Seq(b.blockHash), v2, bonds)
          f       <- createBlock[Task](Seq(c.blockHash), v3, bonds)
          g       <- createBlock[Task](Seq(d.blockHash, e.blockHash), v1, bonds)
          h       <- createBlock[Task](Seq(e.blockHash, f.blockHash), v2, bonds)
          i       <- createBlock[Task](Seq(g.blockHash), v1, bonds)
          dag     <- blockDagStorage.getRepresentation
          latestBlocks = HashMap[Validator, BlockHash](
            v1 -> g.blockHash,
            v2 -> h.blockHash,
            v3 -> i.blockHash
          )
          scores <- Estimator.lmdScoring(dag, latestBlocks)
          _ = scores shouldEqual Map(
            genesis.blockHash -> (3 + 5 + 7),
            a.blockHash       -> (3 + 7),
            b.blockHash       -> 5,
            d.blockHash       -> (3 + 7),
            e.blockHash       -> 5,
            g.blockHash       -> (3 + 7),
            h.blockHash       -> 5,
            i.blockHash       -> 7
          )
        } yield ()
  }

  "lmdMainchainGhost" should "pick the correct fork choice tip" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        /* The DAG looks like:
         *
         *        i
         *        |
         *        g   h
         *       | \ /  \
         *       d  e   f
         *      / \/   /
         *     a  b   c
         *      \ |  /
         *       genesis
         */

        val v1     = generateValidator("Validator One")
        val v2     = generateValidator("Validator Two")
        val v3     = generateValidator("Validator Three")
        val v1Bond = Bond(v1, 3)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 7)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          a       <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
          b       <- createBlock[Task](Seq(genesis.blockHash), v2, bonds)
          c       <- createBlock[Task](Seq(genesis.blockHash), v3, bonds)
          d       <- createBlock[Task](Seq(a.blockHash, b.blockHash), v1, bonds)
          e       <- createBlock[Task](Seq(b.blockHash), v2, bonds)
          f       <- createBlock[Task](Seq(c.blockHash), v3, bonds)
          g       <- createBlock[Task](Seq(d.blockHash, e.blockHash), v1, bonds)
          h       <- createBlock[Task](Seq(e.blockHash, f.blockHash), v2, bonds)
          i       <- createBlock[Task](Seq(g.blockHash), v1, bonds)
          dag     <- blockDagStorage.getRepresentation
          latestBlocks = HashMap[Validator, BlockHash](
            v1 -> g.blockHash,
            v2 -> h.blockHash,
            v3 -> i.blockHash
          )
          tips <- Estimator.tips(dag, genesis.blockHash, latestBlocks)
          _    = tips.head shouldEqual i.blockHash
        } yield ()
  }

}
