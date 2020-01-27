package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.casper.consensus.{Bond, Era}
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.highway.mocks.MockMessageProducer
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.dag.DagStorage
import monix.eval.Task
import org.scalatest._

class MessageProducerSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  behavior of "collectEquivocators"

  it should "gather equivocators from the grandparent, parent and child eras" in testFixture {
    implicit timer => implicit db =>
      new Fixture(length = 4 * eraDuration) {

        // Create an equivocation in an era by storing two messages that don't quote each other.
        // Return the block hash we can use as key block for the next era.
        def equivocate(validator: String, era: Era): Task[BlockHash] = {
          val mp = new MockMessageProducer[Task](validator)
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
                  mainParent = genesis.messageHash,
                  justifications = justifications,
                  isBookingBlock = false
                )
            _ <- mp.ballot(
                  era.keyBlockHash,
                  roundId = Ticks(era.endTick),
                  target = genesis.messageHash,
                  justifications = justifications
                )
          } yield b.messageHash
        }

        override lazy val bonds = List(
          Bond("Alice").withStake(state.BigInt("1000")),
          Bond("Bob").withStake(state.BigInt("2000")),
          Bond("Charlie").withStake(state.BigInt("3000")),
          Bond("Dave").withStake(state.BigInt("4000")),
          Bond("Eve").withStake(state.BigInt("5000")),
          Bond("Fred").withStake(state.BigInt("6000"))
        )

        // Make an era tree where we'll put some equivocations into various ones
        // in the hierarchy and check that the right ones are gathered.
        // eA - eB
        //    \ eC - eD
        //         \ eE - eF

        override def test =
          for {
            _ <- insertGenesis()

            eA  <- addGenesisEra()
            bAB <- equivocate("Alice", eA)
            bAC <- equivocate("Alice", eA)
            bAD <- equivocate("Alice", eA)
            bAE <- equivocate("Alice", eA)

            eB <- eA.addChildEra(bAB)
            _  <- equivocate("Bob", eB)

            eC  <- eA.addChildEra(bAC)
            bCF <- equivocate("Charlie", eC)

            eD <- eC.addChildEra(bAD)
            _  <- equivocate("Dave", eD)

            eE <- eC.addChildEra(bAE)
            _  <- equivocate("Eve", eE)

            eF <- eE.addChildEra(bCF)
            _  <- equivocate("Fred", eF)

            unforgiven <- MessageProducer.collectEquivocators[Task](eF.keyBlockHash)
          } yield {
            unforgiven("Charlie") shouldBe true
            unforgiven("Eve") shouldBe true
            unforgiven("Fred") shouldBe true
            unforgiven.map(x => new String(x.toByteArray)) should have size 3
          }
      }
  }
}
