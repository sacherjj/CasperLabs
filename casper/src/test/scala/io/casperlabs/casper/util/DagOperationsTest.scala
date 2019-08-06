package io.casperlabs.casper.util

import cats.{Id, Monad}
import com.github.ghik.silencer.silent
import cats.implicits._
import io.casperlabs.blockstorage.BlockMetadata
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.scalatestcontrib._
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.BitSet

@silent("deprecated")
@silent("is never used")
class DagOperationsTest extends FlatSpec with Matchers with BlockGenerator with DagStorageFixture {

  "bfTraverseF" should "lazily breadth-first traverse a DAG with effectful neighbours" in {
    implicit val intKey = DagOperations.Key.identity[Int]
    val stream          = DagOperations.bfTraverseF[Id, Int](List(1))(i => List(i * 2, i * 3))
    stream.take(10).toList shouldBe List(1, 2, 3, 4, 6, 9, 8, 12, 18, 27)
  }

  "bfToposortTraverseF" should "lazily breadth-first and order by rank when traverse a DAG with effectful neighbours" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * DAG Looks like this:
         *
         *        b6   b7
         *       |  \ /  \
         *       |   b4  b5
         *       |    \ /
         *       b2    b3
         *         \  /
         *          b1
         *           |
         *         genesis
         */
        for {
          genesis <- createBlock[Task](Seq.empty)
          b1      <- createBlock[Task](Seq(genesis.blockHash))
          b2      <- createBlock[Task](Seq(b1.blockHash))
          b3      <- createBlock[Task](Seq(b1.blockHash))
          b4      <- createBlock[Task](Seq(b3.blockHash))
          b5      <- createBlock[Task](Seq(b3.blockHash))
          b6      <- createBlock[Task](Seq(b2.blockHash, b4.blockHash))
          b7      <- createBlock[Task](Seq(b4.blockHash, b5.blockHash))

          dag <- dagStorage.getRepresentation
          stream = DagOperations.bfToposortTraverseF[Task](List(BlockMetadata.fromBlock(genesis))) {
            b =>
              dag
                .children(b.blockHash)
                .flatMap(_.toList.traverse(l => dag.lookup(l).map(_.get)))
          }
          result <- stream.toList.map(_.map(_.rank) shouldBe List(0, 1, 2, 3, 4, 5, 6, 7))
        } yield result
  }

  "Greatest common ancestor" should "be computed properly" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         * DAG Looks like this:
         *
         *        b6   b7
         *       |  \ /  \
         *       |   b4  b5
         *       |    \ /
         *       b2    b3
         *         \  /
         *          b1
         *           |
         *         genesis
         */
        for {
          genesis <- createBlock[Task](Seq.empty)
          b1      <- createBlock[Task](Seq(genesis.blockHash))
          b2      <- createBlock[Task](Seq(b1.blockHash))
          b3      <- createBlock[Task](Seq(b1.blockHash))
          b4      <- createBlock[Task](Seq(b3.blockHash))
          b5      <- createBlock[Task](Seq(b3.blockHash))
          b6      <- createBlock[Task](Seq(b2.blockHash, b4.blockHash))
          b7      <- createBlock[Task](Seq(b4.blockHash, b5.blockHash))

          dag <- dagStorage.getRepresentation

          _      <- DagOperations.greatestCommonAncestorF[Task](b1, b5, genesis, dag) shouldBeF b1
          _      <- DagOperations.greatestCommonAncestorF[Task](b3, b2, genesis, dag) shouldBeF b1
          _      <- DagOperations.greatestCommonAncestorF[Task](b6, b7, genesis, dag) shouldBeF b1
          _      <- DagOperations.greatestCommonAncestorF[Task](b2, b2, genesis, dag) shouldBeF b2
          result <- DagOperations.greatestCommonAncestorF[Task](b3, b7, genesis, dag) shouldBeF b3
        } yield result
  }

  "uncommon ancestors" should "be computed properly" in withStorage {
    implicit blockStorage =>
      implicit dagStorage =>
        /*
         *  DAG Looks like this:
         *
         *         b6   b7
         *        |  \ / |
         *        b4  b5 |
         *          \ |  |
         *            b3 |
         *            |  |
         *           b1  b2
         *            |  /
         *          genesis
         */
        implicit def toMetadata = BlockMetadata.fromBlock _
        for {
          genesis <- createBlock[Task](Seq.empty)
          b1      <- createBlock[Task](Seq(genesis.blockHash))
          b2      <- createBlock[Task](Seq(genesis.blockHash))
          b3      <- createBlock[Task](Seq(b1.blockHash))
          b4      <- createBlock[Task](Seq(b3.blockHash))
          b5      <- createBlock[Task](Seq(b3.blockHash))
          b6      <- createBlock[Task](Seq(b4.blockHash, b5.blockHash))
          b7      <- createBlock[Task](Seq(b2.blockHash, b5.blockHash))

          dag <- dagStorage.getRepresentation

          ordering <- dag.deriveOrdering(0L)
          _ <- DagOperations.uncommonAncestors(Vector(b6, b7), dag)(Monad[Task], ordering) shouldBeF Map(
                toMetadata(b6) -> BitSet(0),
                toMetadata(b4) -> BitSet(0),
                toMetadata(b7) -> BitSet(1),
                toMetadata(b2) -> BitSet(1)
              )

          _ <- DagOperations.uncommonAncestors(Vector(b6, b3), dag)(Monad[Task], ordering) shouldBeF Map(
                toMetadata(b6) -> BitSet(0),
                toMetadata(b4) -> BitSet(0),
                toMetadata(b5) -> BitSet(0)
              )

          _ <- DagOperations.uncommonAncestors(Vector(b2, b4, b5), dag)(Monad[Task], ordering) shouldBeF Map(
                toMetadata(b2) -> BitSet(0),
                toMetadata(b4) -> BitSet(1),
                toMetadata(b5) -> BitSet(2),
                toMetadata(b3) -> BitSet(1, 2),
                toMetadata(b1) -> BitSet(1, 2)
              )

          result <- DagOperations.uncommonAncestors(Vector(b1), dag)(Monad[Task], ordering) shouldBeF Map
                     .empty[BlockMetadata, BitSet]
        } yield result
  }

}
