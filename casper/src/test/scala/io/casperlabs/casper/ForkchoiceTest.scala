package io.casperlabs.casper

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.util.DagOperations
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

@silent("is never used")
class ForkchoiceTest extends FlatSpec with Matchers with BlockGenerator with DagStorageFixture {
  "Estimator on empty latestMessages" should "return the genesis regardless of DAG" in withStorage {
    implicit blockStorage => implicit dagStorage =>
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
        dag <- dagStorage.getRepresentation
        forkchoice <- Estimator.tips[Task](
                       dag,
                       genesis.blockHash,
                       Map.empty[Estimator.Validator, Estimator.BlockHash]
                     )
      } yield forkchoice.head should be(genesis.blockHash)
  }

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "Estimator on Simple DAG" should "return the appropriate score map and forkchoice" in withStorage {
    implicit blockStorage => implicit dagStorage =>
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
        dag          <- dagStorage.getRepresentation
        latestBlocks <- dag.latestMessageHashes
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
    implicit blockStorage => implicit dagStorage =>
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
        dag          <- dagStorage.getRepresentation
        latestBlocks <- dag.latestMessageHashes
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
    implicit blockStorage =>
      implicit dagStorage =>
        /* The DAG looks like (|| is a main parent)
         *
         *
         *      // ---- e
         *   d  ||  f   |
         *   \\//  ||   |
         *     a    b   c
         *      \\ ||  //
         *       genesis
         */

        val v1     = generateValidator("Validator One")
        val v2     = generateValidator("Validator Two")
        val v3     = generateValidator("Validator Three")
        val v1Bond = Bond(v1, 7)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 3)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)
        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          a <- createBlock[Task](
                Seq(genesis.blockHash),
                v1,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
              )
          b <- createBlock[Task](
                Seq(genesis.blockHash),
                v2,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
              )
          c <- createBlock[Task](
                Seq(genesis.blockHash),
                v3,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
              )
          d <- createBlock[Task](
                Seq(a.blockHash),
                v1,
                bonds,
                justifications =
                  Map(v1 -> a.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
              )
          e <- createBlock[Task](
                Seq(a.blockHash, c.blockHash),
                v3,
                bonds,
                justifications = Map(v1 -> a.blockHash, v2 -> genesis.blockHash, v3 -> c.blockHash)
              )
          f <- createBlock[Task](
                Seq(b.blockHash),
                v2,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> b.blockHash, v3 -> genesis.blockHash)
              )
          dag          <- dagStorage.getRepresentation
          latestBlocks <- dag.latestMessageHashes
          lca          <- DagOperations.latestCommonAncestorF(dag, latestBlocks.values.toList)
          scores       <- Estimator.lmdScoring(dag, lca, latestBlocks)
          _ = scores shouldEqual Map(
            genesis.blockHash -> (3 + 5 + 7),
            a.blockHash       -> (3 + 7),
            b.blockHash       -> 5,
            d.blockHash       -> 7,
            e.blockHash       -> 3,
            f.blockHash       -> 5
          )
        } yield ()
  }

  it should "propagate fixed weights on a DAG" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /* The DAG looks like:
         *
         *
         *            ---i
         *          /    |
         *        g   h  |
         *       | \ /  \|
         *       d  e   f
         *      / \/   /
         *     a  b   c
         *      \ |  /
         *       genesis
         */
        val v1     = generateValidator("One")
        val v2     = generateValidator("Two")
        val v3     = generateValidator("Three")
        val v1Bond = Bond(v1, 7)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 3)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          a <- createBlock[Task](
                Seq(genesis.blockHash),
                v1,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
              )
          b <- createBlock[Task](
                Seq(genesis.blockHash),
                v2,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
              )
          c <- createBlock[Task](
                Seq(genesis.blockHash),
                v3,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> genesis.blockHash)
              )
          d <- createBlock[Task](
                Seq(a.blockHash, b.blockHash),
                v1,
                bonds,
                justifications = Map(v1 -> a.blockHash, v2 -> b.blockHash, v3 -> genesis.blockHash)
              )
          e <- createBlock[Task](
                Seq(b.blockHash),
                v2,
                bonds,
                justifications = Map(v1 -> genesis.blockHash, v2 -> b.blockHash, v3 -> c.blockHash)
              )
          f <- createBlock[Task](
                Seq(c.blockHash),
                v3,
                bonds,
                justifications =
                  Map(v1 -> genesis.blockHash, v2 -> genesis.blockHash, v3 -> c.blockHash)
              )
          g <- createBlock[Task](
                Seq(d.blockHash, e.blockHash),
                v1,
                bonds,
                justifications = Map(v1 -> d.blockHash, v2 -> e.blockHash, v3 -> genesis.blockHash)
              )
          h <- createBlock[Task](
                Seq(e.blockHash, f.blockHash),
                v2,
                bonds,
                justifications = Map(v1 -> genesis.blockHash, v2 -> e.blockHash, v3 -> f.blockHash)
              )
          i <- createBlock[Task](
                Seq(g.blockHash, f.blockHash),
                v3,
                bonds,
                justifications = Map(v1 -> g.blockHash, v2 -> h.blockHash, v3 -> f.blockHash)
              )
          dag          <- dagStorage.getRepresentation
          latestBlocks <- dag.latestMessageHashes
          lca          <- DagOperations.latestCommonAncestorF(dag, latestBlocks.values.toList)
          scores       <- Estimator.lmdScoring(dag, lca, latestBlocks)
          _ = scores shouldEqual Map(
            genesis.blockHash -> (3 + 5 + 7),
            a.blockHash       -> (3 + 7),
            b.blockHash       -> 5,
            d.blockHash       -> (3 + 7),
            e.blockHash       -> 5,
            g.blockHash       -> (3 + 7),
            h.blockHash       -> 5,
            i.blockHash       -> 3
          )
        } yield ()
  }

  it should "stop traversing DAG when reaches the stop hash" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        /* The DAG looks like:
         *
         *          m
         *        / | \
         *       j  k  l
         *      / \/   |
         *     g  h   i
         *      \ |  /
         *        f
         *      / |
         *     d  e
         *    |   | \
         *    a   b  c
         *     \  | /
         *     genesis
         *
         */

        val v1     = generateValidator("One")
        val v2     = generateValidator("Two")
        val v3     = generateValidator("Three")
        val v1Bond = Bond(v1, 7)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 3)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)

        for {
          genesis <- createBlock[Task](Seq(), ByteString.EMPTY)
          a <- createBlock[Task](
                Seq(genesis.blockHash),
                v1,
                bonds,
                justifications = Map(ByteString.EMPTY -> genesis.blockHash)
              )
          b <- createBlock[Task](
                Seq(genesis.blockHash),
                v2,
                bonds,
                justifications = Map(ByteString.EMPTY -> genesis.blockHash)
              )
          c <- createBlock[Task](
                Seq(genesis.blockHash),
                v3,
                bonds,
                justifications = Map(ByteString.EMPTY -> genesis.blockHash)
              )
          d <- createBlock[Task](
                Seq(a.blockHash),
                v1,
                bonds,
                justifications = Map(v1 -> a.blockHash)
              )
          e <- createBlock[Task](
                Seq(b.blockHash, c.blockHash),
                v2,
                bonds,
                justifications = Map(v2 -> b.blockHash, v3 -> c.blockHash)
              )
          f <- createBlock[Task](
                Seq(d.blockHash, e.blockHash),
                v2,
                bonds,
                justifications = Map(v1 -> d.blockHash, v2 -> e.blockHash)
              )
          g <- createBlock[Task](
                Seq(f.blockHash),
                v1,
                bonds,
                justifications = Map(v2 -> f.blockHash)
              )
          h <- createBlock[Task](
                Seq(f.blockHash),
                v2,
                bonds,
                justifications = Map(v2 -> f.blockHash)
              )
          i <- createBlock[Task](
                Seq(f.blockHash),
                v3,
                bonds,
                justifications = Map(v2 -> f.blockHash)
              )
          j <- createBlock[Task](
                Seq(g.blockHash, h.blockHash),
                v1,
                bonds,
                justifications = Map(v1 -> g.blockHash, v2 -> h.blockHash)
              )
          k <- createBlock[Task](
                Seq(h.blockHash),
                v2,
                bonds,
                justifications = Map(v1 -> h.blockHash)
              )
          l <- createBlock[Task](
                Seq(i.blockHash),
                v3,
                bonds,
                justifications = Map(v3 -> i.blockHash)
              )
          m <- createBlock[Task](
                Seq(j.blockHash, k.blockHash, l.blockHash),
                v2,
                bonds,
                justifications = Map(v1 -> j.blockHash, v2 -> k.blockHash, v3 -> l.blockHash)
              )
          dag          <- blockDagStorage.getRepresentation
          latestBlocks <- dag.latestMessageHashes
          lca          <- DagOperations.latestCommonAncestorF(dag, latestBlocks.values.toList)
          scores <- Estimator
                     .lmdScoring(dag, lca, latestBlocks)
          _ = scores shouldEqual Map(
            f.blockHash -> (7 + 5 + 3),
            g.blockHash -> (7 + 5),
            i.blockHash -> 3,
            j.blockHash -> (7 + 5),
            l.blockHash -> 3,
            m.blockHash -> 5
          )
        } yield ()

  }

  "lmdMainchainGhost" should "pick the correct fork choice tip" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /* The DAG looks like:
         *
         *
         *            ---i
         *          /    |
         *        g   h  |
         *       | \ /  \|
         *       d  e   f
         *      / \/   /
         *     a  b   c
         *      \ |  /
         *       genesis
         */
        val v1     = generateValidator("Validator One")
        val v2     = generateValidator("Validator Two")
        val v3     = generateValidator("Validator Three")
        val v1Bond = Bond(v1, 7)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 3)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)

        for {
          genesis      <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          a            <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
          b            <- createBlock[Task](Seq(genesis.blockHash), v2, bonds)
          c            <- createBlock[Task](Seq(genesis.blockHash), v3, bonds)
          d            <- createBlock[Task](Seq(a.blockHash, b.blockHash), v1, bonds)
          e            <- createBlock[Task](Seq(b.blockHash), v2, bonds)
          f            <- createBlock[Task](Seq(c.blockHash), v3, bonds)
          g            <- createBlock[Task](Seq(d.blockHash, e.blockHash), v1, bonds)
          h            <- createBlock[Task](Seq(e.blockHash, f.blockHash), v2, bonds)
          i            <- createBlock[Task](Seq(g.blockHash, f.blockHash), v3, bonds)
          dag          <- dagStorage.getRepresentation
          latestBlocks <- dag.latestMessageHashes
          tips         <- Estimator.tips(dag, genesis.blockHash, latestBlocks)
          _            = tips.head shouldEqual i.blockHash
        } yield ()
  }

}
