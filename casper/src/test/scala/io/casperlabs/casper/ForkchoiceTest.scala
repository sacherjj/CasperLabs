package io.casperlabs.casper

import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.DagRepresentation
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, DagStorageFixture}
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.equivocations.{EquivocationDetector, EquivocationsTracker}
import monix.eval.Task
import org.scalatest.{Assertion, FlatSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.Gen

import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.{HashMap, Map}
import scala.util.Random

@silent("is never used")
class ForkchoiceTest
    extends FlatSpec
    with Matchers
    with GeneratorDrivenPropertyChecks
    with BlockGenerator
    with DagStorageFixture {

  "Estimator on empty latestMessages" should "return the genesis regardless of DAG" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
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
                       Map.empty[Estimator.Validator, Estimator.BlockHash],
                       EquivocationsTracker.empty
                     )
      } yield forkchoice.head should be(genesis.blockHash)
  }

  // See https://docs.google.com/presentation/d/1znz01SF1ljriPzbMoFV0J127ryPglUYLFyhvsb-ftQk/edit?usp=sharing slide 29 for diagram
  "Estimator on Simple DAG" should "return the appropriate score map and forkchoice" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
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
                       latestBlocks,
                       EquivocationsTracker.empty
                     )
        _      = forkchoice.head should be(b6.blockHash)
        result = forkchoice(1) should be(b8.blockHash)
      } yield result
  }

  // See [[/docs/casper/images/no_finalizable_block_mistake_with_no_disagreement_check.png]]
  "Estimator on flipping forkchoice DAG" should "return the appropriate score map and forkchoice" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v3     = generateValidator("V3")
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
                       latestBlocks,
                       EquivocationsTracker.empty
                     )
        _      = forkchoice.head should be(b8.blockHash)
        result = forkchoice(1) should be(b7.blockHash)
      } yield result
  }

  // See [[casper/src/test/resources/casper/tipsHavingEquivocating.png]]
  "Estimator on DAG having validators equivocated" should "return the appropriate score map and main parent" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      val v1     = generateValidator("V1")
      val v2     = generateValidator("V2")
      val v1Bond = Bond(v1, 5)
      val v2Bond = Bond(v2, 3)
      val bonds  = Seq(v1Bond, v2Bond)

      for {
        genesis      <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
        a1           <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
        a2           <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
        b            <- createBlock[Task](Seq(a2.blockHash, genesis.blockHash), v2, bonds)
        c            <- createBlock[Task](Seq(b.blockHash, a1.blockHash), v1, bonds)
        dag          <- dagStorage.getRepresentation
        latestBlocks <- dag.latestMessageHashes
        // Set the equivocationsTracker manually
        equivocationsTracker = new EquivocationsTracker(Map(v2 -> genesis.getHeader.rank))
        equivocatingValidators <- EquivocationDetector.detectVisibleFromJustifications(
                                   dag,
                                   latestBlocks,
                                   equivocationsTracker
                                 )
        _      = equivocatingValidators shouldBe Set(v1)
        scores <- Estimator.lmdScoring(dag, genesis.blockHash, latestBlocks, equivocatingValidators)
        _ = scores shouldBe Map(
          genesis.blockHash -> 3L,
          a2.blockHash      -> 3L,
          b.blockHash       -> 3L,
          c.blockHash       -> 0L
        )

        tips <- Estimator.tips(
                 dag,
                 genesis.blockHash,
                 equivocationsTracker
               )
        _ = tips.head shouldBe c.blockHash
      } yield ()
  }

  /**
    * Property-based test for lmdScoring when having equivocation.
    *
    * Randomly chooses a subset of bonded validators to equivocate
    * and then validates that lmdScoring returns correct results.
    *
    * @param dag The block dag
    * @param bonds Bonded validators and their stakes
    * @param supporterForBlocks Supported validators for each block when traversal in lmdScoring
    * @param latestMessageHashes The latest messages from currently bonded validators
    */
  def testLmdScoringWithEquivocation(
      dag: DagRepresentation[Task],
      bonds: Seq[Bond],
      supporterForBlocks: Map[BlockHash, Seq[Validator]],
      latestMessageHashes: Map[Validator, BlockHash]
  ): Unit = {
    val equivocatorsGen: Gen[Set[Validator]] =
      for {
        n   <- Gen.choose(0, bonds.size)
        idx <- Gen.pick(n, bonds)
      } yield idx.map(_.validatorPublicKey).toSet

    val lca = DagOperations
      .latestCommonAncestorsMainParent(dag, latestMessageHashes.values.toList)
      .runSyncUnsafe(1.second)

    forAll(equivocatorsGen) { equivocators: Set[Validator] =>
      val weightMap = bonds.map {
        case Bond(validator, stake) =>
          if (equivocators.contains(validator))
            (validator, 0L)
          else {
            (validator, stake)
          }
      }.toMap
      val expectScores = supporterForBlocks.mapValues(_.map(weightMap).sum)
      val scores = Estimator
        .lmdScoring(dag, lca, latestMessageHashes, equivocators)
        .runSyncUnsafe(5.seconds)

      scores shouldBe expectScores
    }
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

        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v3     = generateValidator("V3")
        val v1Bond = Bond(v1, 7)
        val v2Bond = Bond(v2, 5)
        val v3Bond = Bond(v3, 3)
        val bonds  = Seq(v1Bond, v2Bond, v3Bond)
        for {
          genesis      <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
          a            <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
          b            <- createBlock[Task](Seq(genesis.blockHash), v2, bonds)
          c            <- createBlock[Task](Seq(genesis.blockHash), v3, bonds)
          d            <- createBlock[Task](Seq(a.blockHash), v1, bonds)
          e            <- createBlock[Task](Seq(a.blockHash, c.blockHash), v3, bonds)
          f            <- createBlock[Task](Seq(b.blockHash), v2, bonds)
          dag          <- dagStorage.getRepresentation
          latestBlocks <- dag.latestMessageHashes
          supportersWithoutEquivocating = Map(
            genesis.blockHash -> Seq(v1, v2, v3),
            a.blockHash       -> Seq(v1, v3),
            b.blockHash       -> Seq(v2),
            d.blockHash       -> Seq(v1),
            e.blockHash       -> Seq(v3),
            f.blockHash       -> Seq(v2)
          )
          _ = testLmdScoringWithEquivocation(
            dag,
            bonds,
            supportersWithoutEquivocating,
            latestBlocks
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
        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v3     = generateValidator("V3")
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

          supportersWithoutEquivocating = Map(
            genesis.blockHash -> Seq(v1, v2, v3),
            a.blockHash       -> Seq(v1, v3),
            b.blockHash       -> Seq(v2),
            d.blockHash       -> Seq(v1, v3),
            e.blockHash       -> Seq(v2),
            g.blockHash       -> Seq(v1, v3),
            h.blockHash       -> Seq(v2),
            i.blockHash       -> Seq(v3)
          )
          _ = testLmdScoringWithEquivocation(
            dag,
            bonds,
            supportersWithoutEquivocating,
            latestBlocks
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
          a       <- createBlock[Task](Seq(genesis.blockHash), v1, bonds)
          b       <- createBlock[Task](Seq(genesis.blockHash), v2, bonds)
          c       <- createBlock[Task](Seq(genesis.blockHash), v3, bonds)
          d       <- createBlock[Task](Seq(a.blockHash), v1, bonds)
          e       <- createBlock[Task](Seq(b.blockHash, c.blockHash), v2, bonds)
          f       <- createBlock[Task](Seq(d.blockHash, e.blockHash), v2, bonds)
          g       <- createBlock[Task](Seq(f.blockHash), v1, bonds)
          h       <- createBlock[Task](Seq(f.blockHash), v2, bonds)
          i       <- createBlock[Task](Seq(f.blockHash), v3, bonds)
          j       <- createBlock[Task](Seq(g.blockHash, h.blockHash), v1, bonds)
          k       <- createBlock[Task](Seq(h.blockHash), v2, bonds)
          l <- createBlock[Task](
                Seq(i.blockHash),
                v3,
                bonds,
                justifications = Map(v3 -> i.blockHash)
              )
          m            <- createBlock[Task](Seq(j.blockHash, k.blockHash, l.blockHash), v2, bonds)
          dag          <- blockDagStorage.getRepresentation
          latestBlocks <- dag.latestMessageHashes
          supportersWithoutEquivocating = Map(
            f.blockHash -> Seq(v1, v2, v3),
            g.blockHash -> Seq(v1, v2),
            i.blockHash -> Seq(v3),
            j.blockHash -> Seq(v1, v2),
            l.blockHash -> Seq(v3),
            m.blockHash -> Seq(v2)
          )
          _ = testLmdScoringWithEquivocation(
            dag,
            bonds,
            supportersWithoutEquivocating,
            latestBlocks
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
        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v3     = generateValidator("V3")
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
          tips         <- Estimator.tips(dag, genesis.blockHash, latestBlocks, EquivocationsTracker.empty)
          _            = tips.head shouldEqual i.blockHash
        } yield ()
  }

}
