package io.casperlabs.casper.highway

import cats.effect.Timer
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{state, Bond, Era}
import io.casperlabs.casper.{DeploySelection, PrettyPrinter, ValidatorIdentity}
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import monix.eval.Task
import org.scalatest.FlatSpec

import scala.concurrent.duration._

@silent("is never used")
class ForkChoiceTest extends FlatSpec with HighwayFixture {

  behavior of "ForkChoiceTest"

  "fromKeyBlock" should "return fork choice in the simplest case" in testFixture {
    implicit timer => implicit db =>
      new ForkChoiceTestFixture(eraDuration) {

        // key_block - A

        override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3))

        override def test: Task[Unit] =
          for {
            _          <- insertGenesis()
            genesisEra <- addGenesisEra()
            alice      = normal(aliceMP, genesisEra) _
            a          <- alice(genesisEra.keyBlockHash)
            forkChoice <- ForkChoice.create[Task]().fromKeyBlock(genesisEra.keyBlockHash)
          } yield {
            assert(forkChoice.block.messageHash == a)
            assert(forkChoice.justifications == Set.empty)
          }
      }
  }

  it should "return fork choice for a blockchain" in testFixture { implicit timer => implicit db =>
    new ForkChoiceTestFixture(eraDuration) {

      // key_block - A - B

      override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3, Bob -> 2))

      override def test: Task[Unit] =
        for {
          _          <- insertGenesis()
          genesisEra <- addGenesisEra()
          alice      = normal(aliceMP, genesisEra) _
          bob        = normal(bobMP, genesisEra) _
          a          <- alice(genesisEra.keyBlockHash)
          b          <- bob(a)
          forkChoice <- ForkChoice.create[Task]().fromKeyBlock(genesisEra.keyBlockHash)
        } yield {
          assert(forkChoice.block.messageHash == b)
          assert(forkChoice.justifications.isEmpty)
        }
    }
  }

  it should "return fork choice within single era" in testFixture { implicit timer => implicit db =>
    // Weights: A -> 3, B -> 4, C -> 5
    //
    //                      era boundary
    //             A1 - A2     |
    //            /       \    |
    //  key_block         B1   |
    //            \            |
    //            C1 - C2      |
    //
    // fork choice - B1
    // latest messages - {C2}, B1 is the fork choice and A2 is its p-dag ancestor

    new ForkChoiceTestFixture(
      length = eraDuration
    ) {

      override def test: Task[Unit] =
        for {
          _          <- insertGenesis()
          genesisEra <- addGenesisEra()
          alice      = normal(aliceMP, genesisEra) _
          bob        = normal(bobMP, genesisEra) _
          charlie    = normal(charlieMP, genesisEra) _
          a1         <- alice(genesis.messageHash)
          c1         <- charlie(genesis.messageHash)
          a2         <- alice(a1)
          b1         <- bob(a2)
          c2         <- charlie(c1)
          forkChoice <- ForkChoice.create[Task]().fromKeyBlock(genesisEra.keyBlockHash)
        } yield {
          assert(forkChoice.block.messageHash == b1)
          assert(forkChoice.justifications.map(_.messageHash) == Set(c2))
        }
  }
  }

  it should "not go past the key block when calculating fork choice" in (pending)

  it should "not go past the block when we have majority voting for one block" in (pending)

  it should "compute fork choice across multiple eras (no equivocators)" in {
    // three eras (era-1, era-2, era-3)
    // key block in era-1
    // latest messages in era-3
    // no equivocators
    pending
  }

  it should "compute fork choice across mutliple eras (with equivocators)" in {
    // three eras (era-1, era-2, era-3)
    // key block in era-1
    // latest messages in era-3
    // equivocators in eras era-1, era-2 and era-3
    // chosen fork choice should not count equivocators' votes
    pending
  }

  it should "return a key block when there's no correct descendants" in (pending)

  it should "return reduced justifications of the fork choice" in (pending)

  it should "return the parent-era voting-only ballots as justifications together with child-era blocks" in (pending)
  it should "not choose an equivocator as the fork choice" in (pending)
  it should "not return parent-era voting-only ballots as justifications once they are included in the child-era blocks" in (pending)

  abstract class ForkChoiceTestFixture(length: FiniteDuration)(
      implicit
      timer: Timer[Task],
      db: SQLiteStorage.CombinedStorage[Task]
  ) extends Fixture(length) {

    implicit val deployBuffer    = DeployBuffer.create[Task](chainName, minTtl = Duration.Zero)
    implicit val deploySelection = DeploySelection.create[Task](sizeLimitBytes = Int.MaxValue)

    lazy val Alice   = Ed25519.newKeyPair
    lazy val Bob     = Ed25519.newKeyPair
    lazy val Charlie = Ed25519.newKeyPair

    def makeBonds(keys: Map[(Keys.PrivateKey, PublicKey), Int]): List[Bond] =
      keys
        .map { case (keys, stake) => keys._2 -> stake }
        .map {
          case (pk, stake) =>
            Bond(ByteString.copyFrom(pk)).withStake(state.BigInt(stake.toString))
        }
        .toList

    override lazy val bonds: List[Bond] = makeBonds(Map(Alice -> 3, Bob -> 4, Charlie -> 5))

    val aliceMP: MessageProducer[Task]   = createMessageProducer(Alice)
    val bobMP: MessageProducer[Task]     = createMessageProducer(Bob)
    val charlieMP: MessageProducer[Task] = createMessageProducer(Charlie)

    private def createMessageProducer(keys: (Keys.PrivateKey, PublicKey)): MessageProducer[Task] =
      MessageProducer[Task](
        validatorIdentity = ValidatorIdentity(keys._2, keys._1, signatureAlgorithm = Ed25519),
        chainName = chainName,
        upgrades = Seq.empty
      )

    private def produceG(
        mp: MessageProducer[Task],
        parent: BlockHash,
        era: Era,
        withEquivocation: Boolean
    ): Task[BlockHash] =
      for {
        dag    <- DagStorage[Task].getRepresentation
        tips   <- dag.latestInEra(era.keyBlockHash)
        latest <- tips.latestMessages
        justifications = latest.map {
          case (v, ms) => PublicKey(v) -> ms.map(_.messageHash)
        }
        b <- mp.block(
              era.keyBlockHash,
              roundId = Ticks(era.startTick),
              mainParent = parent,
              justifications = justifications,
              isBookingBlock = false
            )
        _ <- mp
              .ballot(
                era.keyBlockHash,
                roundId = Ticks(era.endTick),
                target = parent,
                justifications = justifications
              )
              .whenA(withEquivocation)
      } yield b.messageHash

    def normal(
        mp: MessageProducer[Task],
        era: Era
    )(
        parent: BlockHash
    ): Task[BlockHash] =
      produceG(mp, parent, era, false)

    def equivocate(
        mp: MessageProducer[Task],
        era: Era
    )(
        parent: BlockHash
    ): Task[BlockHash] =
      produceG(mp, parent, era, true)

  }
}
