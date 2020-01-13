package io.casperlabs.casper.util

import cats.data.NonEmptyList
import cats.implicits._
import cats.{Id, Monad}
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.models.Message
import io.casperlabs.shared.Sorting.messageSummaryOrdering
import io.casperlabs.storage.dag.DagRepresentation
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.BitSet

@silent("deprecated")
@silent("is never used")
class DagOperationsTest extends FlatSpec with Matchers with BlockGenerator with StorageFixture {

  "bfTraverseF" should "lazily breadth-first traverse a DAG with effectful neighbours" in {
    implicit val intKey = DagOperations.Key.identity[Int]
    val stream          = DagOperations.bfTraverseF[Id, Int](List(1))(i => List(i * 2, i * 3))
    stream.take(10).toList shouldBe List(1, 2, 3, 4, 6, 9, 8, 12, 18, 27)
  }

  "bfToposortTraverseF" should "lazily breadth-first and order by rank when traverse a DAG with effectful neighbours" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage =>
      _ =>
        /*
         * DAG Looks like this:
         *
         *       b6      b7
         *       |  \ /  |
         *       |  b4   b5
         *       |     \ |
         *       b2      b3
         *         \  /
         *          b1
         *           |
         *         genesis
         */
        val v1 = generateValidator("v1")
        val v2 = generateValidator("v2")
        val v3 = generateValidator("v3")

        for {
          genesis <- createAndStoreMessage[Task](Seq.empty)
          b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
          b2      <- createAndStoreMessage[Task](Seq(b1.blockHash), v1)
          b3      <- createAndStoreMessage[Task](Seq(b1.blockHash), v3)
          b4      <- createAndStoreMessage[Task](Seq(b3.blockHash), v2)
          b5      <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
          b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash), v1)
          b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash), v3)

          dag                <- dagStorage.getRepresentation
          dagTopoOrderingAsc = DagOperations.blockTopoOrderingAsc
          stream = DagOperations.bfToposortTraverseF[Task](Message.fromBlock(genesis).toList) { b =>
            dag
              .children(b.messageHash)
              .flatMap(_.toList.traverse(l => dag.lookup(l).map(_.get)))
          }(Monad[Task], dagTopoOrderingAsc)
          _                   <- stream.toList.map(_.map(_.rank) shouldBe List(0, 1, 2, 2, 3, 3, 4, 4))
          dagTopoOrderingDesc = DagOperations.blockTopoOrderingDesc
          stream2 = DagOperations
            .bfToposortTraverseF[Task](
              Message.fromBlock(b6).toList ++ Message.fromBlock(b7).toList
            ) { b =>
              b.parents.toList.traverse(l => dag.lookup(l).map(_.get))
            }(Monad[Task], dagTopoOrderingDesc)
          _ <- stream2.toList.map(_.map(_.rank) shouldBe List(4, 4, 3, 3, 2, 2, 1, 0))
        } yield ()
  }

  "Greatest common ancestor" should "be computed properly" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage =>
      _ =>
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
          genesis <- createAndStoreMessage[Task](Seq.empty)
          b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
          b2      <- createAndStoreMessage[Task](Seq(b1.blockHash))
          b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
          b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
          b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
          b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash))
          b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))

          dag <- dagStorage.getRepresentation

          _      <- DagOperations.greatestCommonAncestorF[Task](b1, b5, genesis, dag) shouldBeF b1
          _      <- DagOperations.greatestCommonAncestorF[Task](b3, b2, genesis, dag) shouldBeF b1
          _      <- DagOperations.greatestCommonAncestorF[Task](b6, b7, genesis, dag) shouldBeF b1
          _      <- DagOperations.greatestCommonAncestorF[Task](b2, b2, genesis, dag) shouldBeF b2
          result <- DagOperations.greatestCommonAncestorF[Task](b3, b7, genesis, dag) shouldBeF b3
        } yield result
  }

  "Latest Common Ancestor" should "be computed properly for various j-DAGs" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage => _ =>
      val v1 = generateValidator("One")
      val v2 = generateValidator("Two")
      val v3 = generateValidator("Three")

      /* 1) DAG looks like this:
       *
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */
      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        b1             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b2             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        dag            <- dagStorage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)

      /* 2) DAG looks like this:
       *         b2
       *      /  |
       *     b1  |
       *     |  /
       *  genesis
       *
       */
      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        b1             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b2             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        dag            <- dagStorage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)

      /* 3) DAG looks like this:
       * v1  v2  v3
       *
       *         b6
       *         |
       *     b4  b5
       *       \ |
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */

      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        b1             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b2             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        b4             <- createAndStoreMessage[Task](Seq(b3.blockHash), v2)
        b5             <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
        b6             <- createAndStoreMessage[Task](Seq(b5.blockHash), v3)
        dag            <- dagStorage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)

      /* 4) DAG looks like this:
       * v1  v2  v3
       *
       *         b6
       *         |
       *     b4  b5
       *   /     |
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */

      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        b1             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b2             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        b4             <- createAndStoreMessage[Task](Seq(b1.blockHash), v2)
        b5             <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
        b6             <- createAndStoreMessage[Task](Seq(b5.blockHash), v3)
        dag            <- dagStorage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == genesis.blockHash)

      /* 5) DAG looks like this:
       *  b6     b7
       *    \  /
       *     b4  b5
       *    |    |
       * b1  b2  b3
       *  \  |  /
       *  genesis
       *
       */

      for {
        genesis        <- createAndStoreMessage[Task](Seq.empty)
        b1             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b2             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        b3             <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        b4             <- createAndStoreMessage[Task](Seq(b2.blockHash), v2)
        b5             <- createAndStoreMessage[Task](Seq(b3.blockHash), v3)
        b6             <- createAndStoreMessage[Task](Seq(b4.blockHash), v1)
        b7             <- createAndStoreMessage[Task](Seq(b4.blockHash), v3)
        dag            <- dagStorage.getRepresentation
        latestMessages <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestMessages.values.flatten.toList)
              )
      } yield assert(lca.messageHash == b4.blockHash)

      /* 6) DAG looks like:
       *
       *          m
       *            \
       *       j  k  l
       *      /  /   |
       *     g  h   i
       *      \ |  /
       *        f
       *      /
       *     d  e
       *    |    \
       *    a   b  c
       *     \  | /
       *     genesis
       *
       */

      for {
        genesis      <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY)
        a            <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1)
        b            <- createAndStoreMessage[Task](Seq(genesis.blockHash), v2)
        c            <- createAndStoreMessage[Task](Seq(genesis.blockHash), v3)
        d            <- createAndStoreMessage[Task](Seq(a.blockHash), v1, Seq.empty)
        e            <- createAndStoreMessage[Task](Seq(c.blockHash), v2, Seq.empty, Map(v2 -> b.blockHash))
        f            <- createAndStoreMessage[Task](Seq(d.blockHash), v2, Seq.empty, Map(v2 -> e.blockHash))
        g            <- createAndStoreMessage[Task](Seq(f.blockHash), v1, Seq.empty, Map(v1 -> d.blockHash))
        h            <- createAndStoreMessage[Task](Seq(f.blockHash), v2, Seq.empty)
        i            <- createAndStoreMessage[Task](Seq(f.blockHash), v3, Seq.empty, Map(v3 -> c.blockHash))
        j            <- createAndStoreMessage[Task](Seq(g.blockHash), v1, Seq.empty)
        k            <- createAndStoreMessage[Task](Seq(h.blockHash), v2, Seq.empty)
        l            <- createAndStoreMessage[Task](Seq(i.blockHash), v3, Seq.empty)
        m            <- createAndStoreMessage[Task](Seq(l.blockHash), v2, Seq.empty, Map(v2 -> k.blockHash))
        dag          <- dagStorage.getRepresentation
        latestBlocks <- dag.latestMessageHashes
        lca <- DagOperations.latestCommonAncestorsMainParent(
                dag,
                NonEmptyList.fromListUnsafe(latestBlocks.values.flatten.toList)
              )
      } yield assert(lca.messageHash == f.blockHash)
  }

  "uncommon ancestors" should "be computed properly" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage =>
      _ =>
        /*
         *  DAG Looks like this:
         *
         * rank
         *  4        b6   b7
         *          |  \ / |
         *  3       b4  b5 |
         *            \ |  |
         *  2           b3 |
         *              |  |
         *  1          b1  b2
         *              |  /
         *  0         genesis
         */
        implicit def toMessageSummary: Block => Message = Message.fromBlock(_).get
        for {
          genesis <- createAndStoreMessage[Task](Seq.empty)
          b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
          b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
          b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
          b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
          b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
          b6      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))
          b7      <- createAndStoreMessage[Task](Seq(b2.blockHash, b5.blockHash))

          dag <- dagStorage.getRepresentation

          _ <- DagOperations.uncommonAncestors[Task](Vector(b6, b7), dag) shouldBeF Map(
                toMessageSummary(b6) -> BitSet(0),
                toMessageSummary(b4) -> BitSet(0),
                toMessageSummary(b7) -> BitSet(1),
                toMessageSummary(b2) -> BitSet(1)
              )

          _ <- DagOperations.uncommonAncestors[Task](Vector(b6, b3), dag) shouldBeF Map(
                toMessageSummary(b6) -> BitSet(0),
                toMessageSummary(b4) -> BitSet(0),
                toMessageSummary(b5) -> BitSet(0)
              )

          _ <- DagOperations.uncommonAncestors[Task](Vector(b2, b4, b5), dag) shouldBeF Map(
                toMessageSummary(b2) -> BitSet(0),
                toMessageSummary(b4) -> BitSet(1),
                toMessageSummary(b5) -> BitSet(2),
                toMessageSummary(b3) -> BitSet(1, 2),
                toMessageSummary(b1) -> BitSet(1, 2)
              )

          result <- DagOperations.uncommonAncestors[Task](Vector(b1), dag) shouldBeF Map
                     .empty[Message, BitSet]
        } yield result
  }

  "anyDescendantPathExists" should
    "return whether there is a path from any of the possible ancestor blocks to any of the potential descendants" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage => _ =>
      def anyDescendantPathExists(
          dag: DagRepresentation[Task],
          start: Set[Block],
          targets: Set[Block]
      ) =
        DagOperations.anyDescendantPathExists[Task](
          dag,
          start.map(_.blockHash),
          targets.map(_.blockHash)
        )
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
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash))
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))
        dag     <- dagStorage.getRepresentation
        // self
        _ <- anyDescendantPathExists(dag, Set(genesis), Set(genesis)) shouldBeF true
        // any descendant
        _ <- anyDescendantPathExists(dag, Set(b3), Set(b2, b7)) shouldBeF true
        // any ancestor
        _ <- anyDescendantPathExists(dag, Set(b2, b3), Set(b5)) shouldBeF true
        // main parent
        _ <- anyDescendantPathExists(dag, Set(b4), Set(b7)) shouldBeF true
        // secondary parent
        _ <- anyDescendantPathExists(dag, Set(b5), Set(b7)) shouldBeF true
        // not to ancestor
        _ <- anyDescendantPathExists(dag, Set(b2, b4), Set(b1)) shouldBeF false
        // not to sibling
        _ <- anyDescendantPathExists(dag, Set(b2), Set(b3)) shouldBeF false
      } yield ()
  }

  "collectWhereDescendantPathExists" should
    "return from the possible ancestor blocks the ones which have a path to any of the potential descendants" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage => _ =>
      def collect(
          dag: DagRepresentation[Task],
          start: Set[Block],
          targets: Set[Block]
      ) =
        DagOperations.collectWhereDescendantPathExists[Task](
          dag,
          start.map(_.blockHash),
          targets.map(_.blockHash)
        )
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
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash))
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b3      <- createAndStoreMessage[Task](Seq(b1.blockHash))
        b4      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b5      <- createAndStoreMessage[Task](Seq(b3.blockHash))
        b6      <- createAndStoreMessage[Task](Seq(b2.blockHash, b4.blockHash))
        b7      <- createAndStoreMessage[Task](Seq(b4.blockHash, b5.blockHash))
        dag     <- dagStorage.getRepresentation
        // self
        _ <- collect(dag, Set(genesis), Set(genesis)) shouldBeF Set(genesis.blockHash)
        // any descendant
        _ <- collect(dag, Set(b3), Set(b2, b7)) shouldBeF Set(b3.blockHash)
        // any ancestor
        _ <- collect(dag, Set(b2, b3), Set(b5)) shouldBeF Set(b3.blockHash)
        _ <- collect(dag, Set(b1, b3), Set(b5)) shouldBeF Set(b1.blockHash, b3.blockHash)
        // main parent
        _ <- collect(dag, Set(b4), Set(b7)) shouldBeF Set(b4.blockHash)
        // secondary parent
        _ <- collect(dag, Set(b5), Set(b7)) shouldBeF Set(b5.blockHash)
        // not to ancestor
        _ <- collect(dag, Set(b2, b4), Set(b1)) shouldBeF Set.empty
        // not to sibling
        _ <- collect(dag, Set(b2), Set(b3)) shouldBeF Set.empty
      } yield ()
  }

  "swimlaneV" should "return correct stream of blocks even if they are referenced indirectly" in withStorage {
    implicit blockStorage => implicit dagStorage => _ => _ =>
      val v1    = generateValidator("v1")
      val v2    = generateValidator("v2")
      val bonds = Seq(Bond(v1, 10), Bond(v2, 10))

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash), v1, bonds)
        b2      <- createAndStoreMessage[Task](Seq(b1.blockHash), v2, bonds, Map(v1 -> b1.blockHash))
        b3      <- createAndStoreMessage[Task](Seq(b2.blockHash), v1, bonds, Map(v2 -> b2.blockHash))
        dag     <- dagStorage.getRepresentation
        message <- Task.fromTry(Message.fromBlock(b3))
        _ <- DagOperations
              .swimlaneV[Task](v1, message, dag)
              .map(_.messageHash)
              .toList shouldBeF List(b3.blockHash, b1.blockHash)
      } yield ()
  }

}
