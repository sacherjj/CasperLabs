package io.casperlabs.casper.finality

import cats.Functor
import cats.data.NonEmptyList
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.util.BondingUtil.Bond
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.shared.{LogStub, Time}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, IndexedDagStorage}
import monix.eval.Task
import org.scalatest.FlatSpec

class MultiParentFinalizerTest extends FlatSpec with BlockGenerator with StorageFixture {

  behavior of "MultiParentFinalizer"

  // Who creates what doesn't matter in these tests.
  val v1     = generateValidator("A")
  val v2     = generateValidator("Z")
  val v1Bond = Bond(v1, 3)
  val v2Bond = Bond(v2, 3)
  val bonds  = Seq(v1Bond, v2Bond)

  it should "cache block finalization so it doesn't revisit already finalized blocks." in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      for {
        genesis <- createAndStoreBlock[Task](Seq(), ByteString.EMPTY, bonds)
        dag     <- dagStorage.getRepresentation
        multiParentFinalizer <- MultiParentFinalizer.empty[Task](
                                 dag,
                                 genesis.blockHash,
                                 MultiParentFinalizerTest.immediateFinality
                               )
        a                     <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
        b                     <- createAndStoreBlockFull[Task](v2, Seq(genesis, a), Seq.empty, bonds)
        newlyFinalizedBlocksA <- multiParentFinalizer.onNewBlockAdded(b)
        // `b` is in main chain, `a` is secondary parent.
        _ = assert(newlyFinalizedBlocksA.contains(FinalizedBlocks(b.blockHash, Set(a.blockHash))))
        c <- createAndStoreBlockFull[Task](v1, Seq(b, a), Seq.empty, bonds)
        // `c`'s main parent is `b`, secondary is a. Since `a` was already finalized through `b`,
        // it should not be returned now.
        newlyFinalizedBlocksB <- multiParentFinalizer.onNewBlockAdded(c)
        _                     = assert(newlyFinalizedBlocksB.contains(FinalizedBlocks(c.blockHash)))
      } yield ()
  }

  it should "cache LFB from the main chain; not return LFB when new block doesn't vote on LFB's child" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      implicit val noopLog = LogStub[Task]()

      /** `B` is LFB but `C` doesn't vote for any of `B`'s children (empty vote).
        * `MultiParentFinalizer` should cache LFB (`B`) and kick off new LFB calculation from it.
        * Adding `C` should not return any LFB.
        *
        *  G = A = B*
        *      \\= C
        */
      for {
        genesis   <- createAndStoreBlock[Task](Seq(), ByteString.EMPTY, bonds)
        dag       <- dagStorage.getRepresentation
        finalizer <- FinalityDetectorVotingMatrix.of[Task](dag, genesis.blockHash, 0.1)
        implicit0(multiParentFinalizer: MultiParentFinalizer[Task]) <- MultiParentFinalizer
                                                                        .empty[Task](
                                                                          dag,
                                                                          genesis.blockHash,
                                                                          finalizer
                                                                        )
        a        <- createAndStoreBlockFull[Task](v1, Seq(genesis), Seq.empty, bonds)
        nelBonds = NonEmptyList.fromListUnsafe(bonds.toList)
        b <- MultiParentFinalizerTest
              .finalizeBlock[Task](a.blockHash, nelBonds)
              .flatMap(ProtoUtil.unsafeGetBlock[Task](_))
        finalizedA <- multiParentFinalizer.onNewBlockAdded(b)
        _          = assert(finalizedA.contains(FinalizedBlocks(a.blockHash)))

        // `aPrime` is sibiling of `a`, another child of Genesis.
        // Since `a` has already been finalized `aPrime` should never become chosen as new LFB.
        // We are testing whether `MultiParentFinalizer` caches new LFB properly.
        aPrime <- createAndStoreBlockFull[Task](v2, Seq(genesis), Seq.empty, bonds)
        bPrime <- MultiParentFinalizerTest
                   .finalizeBlock[Task](aPrime.blockHash, nelBonds)
                   .flatMap(ProtoUtil.unsafeGetBlock[Task](_))
        finalizedB <- multiParentFinalizer.onNewBlockAdded(bPrime)
        _          = assert(finalizedB.isEmpty)
      } yield ()
  }
}

object MultiParentFinalizerTest extends BlockGenerator {
  import cats.implicits._

  /** Assembles a String that represents a DAG structure.
    * One block per line in the form of: {rank} {main parent hash} {block hash} {validator id}.
    */
  def printDagTopoSort[F[_]: Fs2Compiler: Functor](dag: DagRepresentation[F]): F[String] =
    dag
      .topoSort(startBlockNumber = 0) // Start from Genesis.
      .compile
      .toVector
      .map(_.flatten)
      .map(
        _.map(
          blockInfo =>
            s"${blockInfo.getSummary.getHeader.rank}:\t${PrettyPrinter.buildString(
              blockInfo.getSummary.getHeader.parentHashes.headOption.getOrElse(ByteString.EMPTY)
            )} <- ${PrettyPrinter
              .buildString(blockInfo.getSummary.blockHash)} : ${PrettyPrinter
              .buildString(blockInfo.getSummary.getHeader.validatorPublicKey)}"
        ).mkString("\n")
      )

  /** Creates a chain of blocks that build on top of each other.
    * Starts with `start` block hash.
    *
    * Returns last block hash in chain.
    */
  def createChainOfBlocks[F[_]: MonadThrowable: Time: BlockStorage: IndexedDagStorage: MultiParentFinalizer](
      start: BlockHash,
      bonds: NonEmptyList[Bond]
  ): F[BlockHash] =
    bonds.map(_.validatorPublicKey).toList.foldLeftM(start) {
      case (prevHash, validatorId) =>
        for {
          block <- createAndStoreBlock[F](Seq(prevHash), validatorId, bonds.toList)
          _     <- MultiParentFinalizer[F].onNewBlockAdded(block) // Update finalizer
        } yield block.blockHash
    }

  /** Finalizes a `start` block.
    *
    * To finalize a block, we need level-1 summit (i.e. enough validators has to see enough validators voting for a block).
    * The easiest way to achieve this is to a layer of messages that build on top of block we want to finalize.
    *
    * Returns last block hash in chain.
    */
  def finalizeBlock[F[_]: MonadThrowable: Time: BlockStorage: IndexedDagStorage: MultiParentFinalizer](
      start: BlockHash,
      bonds: NonEmptyList[Bond]
  ): F[BlockHash] =
    for {
      a <- createChainOfBlocks[F](start, bonds)                          // Create level-0 summit
      b <- createAndStoreBlock[F](Seq(a), bonds.head.validatorPublicKey) // Create level-1 summit
    } yield b.blockHash

  // Finalizes block it receives as argument.
  val immediateFinality = new FinalityDetector[Task] {
    override def onNewBlockAddedToTheBlockDag(
        dag: DagRepresentation[Task],
        block: Block,
        latestFinalizedBlock: BlockHash
    ): Task[Option[CommitteeWithConsensusValue]] =
      Task(Some(CommitteeWithConsensusValue(Set.empty, 1L, block.blockHash)))
  }
}
