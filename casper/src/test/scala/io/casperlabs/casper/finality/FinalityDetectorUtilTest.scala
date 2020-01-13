package io.casperlabs.casper.finality

import cats.Monad
import cats.data.StateT
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.util.BondingUtil.Bond
import monix.eval.Task
import org.scalatest.FlatSpec
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.dag.DagRepresentation.Validator

class FinalityDetectorUtilTest extends FlatSpec with BlockGenerator with StorageFixture {

  behavior of "FinalityDetectorUtilTest"

  // Who creates what doesn't matter in these tests.
  val v1     = generateValidator("V1")
  val v2     = generateValidator("V2")
  val v1Bond = Bond(v1, 2)
  val v2Bond = Bond(v2, 3)
  val bonds  = Seq(v1Bond, v2Bond)

  "finalizedIndirectly" should "finalize blocks in the p-past-cone of the block from the main chain" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      _ =>
        //
        // G=A= = =B
        //   \\A1/
        // When B gets finalized A1 gets finalized indirectly.

        for {
          genesis <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
          a       <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
          a1      <- createAndStoreBlockFull[Task](v2, Seq(a), Seq.empty, bonds)
          b       <- createAndStoreBlockFull[Task](v1, Seq(a, a1), Seq.empty, bonds)
          dag     <- dagStorage.getRepresentation
          finalizedIndirectly <- FinalityDetectorUtil.finalizedIndirectly[Task](
                                  b.blockHash,
                                  Set(genesis.blockHash, a.blockHash),
                                  dag
                                )
        } yield assert(finalizedIndirectly == Set(a1.blockHash))
  }

  it should "not consider previously finalized blocks" in withStorage {
    implicit blockStorage => implicit dagStorage => _ => _ =>
      import FinalityDetectorUtilTest._
      // Record how many times (if any) each node was visited.
      type G[A] = StateT[Task, Map[BlockHash, Int], A]

      /**
        *           B = E
        *          //   |
        *         A = D |
        *       // \   \|
        *      G = C = F*
        *
        *  A main chain is G <- C <- F.
        *  When F is finalized it's indirectly finalizing its p-past-cone:
        *  {D, E, B, A}. There are three paths to reach A:
        *  1. F -> D -> A
        *  2. F -> E -> B -> A
        *  3. F -> C -> A
        *  `finalizedIndirectly` should not visit the same node twice.
        *  It should also not traverse subgraphs of already finalized nodes,
        *  i.e. since A is finalized with C, `finalizedIndirectly(F)` should not visit that node.
        */
      for {
        genesis   <- createAndStoreMessage[Task](Seq(), ByteString.EMPTY, bonds)
        a         <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
        b         <- createAndStoreBlockFull[Task](v1, Seq(a), Seq.empty, bonds)
        c         <- createAndStoreBlockFull[Task](v1, Seq(genesis, a), Seq.empty, bonds)
        dag       <- dagStorage.getRepresentation
        stateTDag = stateTDagRepresentation(Task.catsAsync, dag) // For some reason scalac cannot infer this.
        expectedNodesVisitedA = Map(
          c.blockHash -> 1,
          a.blockHash -> 1
        )
        _ <- FinalityDetectorUtil
              .finalizedIndirectly[G](
                c.blockHash,
                Set(genesis.blockHash),
                stateTDag
              )
              .run(Map.empty) shouldBeF ((expectedNodesVisitedA, Set(a.blockHash)))
        d <- createAndStoreBlockFull[Task](v1, Seq(a), Seq.empty, bonds)
        e <- createAndStoreBlockFull[Task](v1, Seq(b), Seq.empty, bonds)
        f <- createAndStoreBlockFull[Task](v1, Seq(c, d, e), Seq.empty, bonds)
        // Notice how neither C nor A are being visited.
        expectedNodesVisitedB = Map(
          f -> 1,
          d -> 1,
          e -> 1,
          b -> 1
        ).map(p => (p._1.blockHash, p._2))
        _ <- FinalityDetectorUtil
              .finalizedIndirectly[G](
                f.blockHash,
                Set(c.blockHash, a.blockHash, genesis.blockHash),
                stateTDag
              )
              .run(Map.empty) shouldBeF (
              (
                expectedNodesVisitedB,
                Set(d.blockHash, e.blockHash, b.blockHash)
              )
            )
      } yield ()
  }

}

object FinalityDetectorUtilTest {
  import cats.implicits._

  def stateTDagRepresentation[A, F[_]: Monad](
      implicit D: DagRepresentation[F]
  ): DagRepresentation[StateT[F, Map[BlockHash, Int], *]] =
    new DagRepresentation[StateT[F, Map[BlockHash, Int], *]] {
      // Record how many times (if any) each node was visited.
      override def lookup(blockHash: BlockHash): StateT[F, Map[BlockHash, Int], Option[Message]] =
        StateT
          .modify[F, Map[BlockHash, Int]](_ |+| Map(blockHash -> 1))
          .flatMapF(_ => D.lookup(blockHash))

      // We're not interested in these
      override def children(blockHash: BlockHash): StateT[F, Map[BlockHash, Int], Set[BlockHash]] =
        ???

      /** Return blocks that having a specify justification */
      override def justificationToBlocks(
          blockHash: BlockHash
      ): StateT[F, Map[BlockHash, Int], Set[BlockHash]] = ???

      override def contains(blockHash: BlockHash): StateT[F, Map[BlockHash, Int], Boolean] = ???

      /** Return block summaries with ranks in the DAG between start and end, inclusive. */
      override def topoSort(
          startBlockNumber: Level,
          endBlockNumber: Level
      ): fs2.Stream[StateT[F, Map[BlockHash, Int], *], Vector[BlockInfo]] = ???

      /** Return block summaries with ranks of blocks in the DAG from a start index to the end. */
      override def topoSort(
          startBlockNumber: Level
      ): fs2.Stream[StateT[F, Map[BlockHash, Int], *], Vector[BlockInfo]] = ???

      override def topoSortTail(
          tailLength: Int
      ): fs2.Stream[StateT[F, Map[BlockHash, Int], *], Vector[BlockInfo]] = ???

      override def latestMessageHash(
          validator: Validator
      ): StateT[F, Map[BlockHash, Int], Set[BlockHash]] = ???

      override def latestMessage(
          validator: Validator
      ): StateT[F, Map[BlockHash, Int], Set[Message]] = ???

      override def latestMessageHashes
          : StateT[F, Map[BlockHash, Int], Map[Validator, Set[BlockHash]]] = ???

      override def latestMessages: StateT[F, Map[BlockHash, Int], Map[Validator, Set[Message]]] =
        ???
    }
}
