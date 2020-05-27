package io.casperlabs.casper.highway

import cats.implicits._
import cats.effect.concurrent.Ref
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import io.casperlabs.casper.highway.mocks.{MockForkChoice, MockMessageProducer}
import io.casperlabs.models.Message
import io.casperlabs.casper.dag.DagOperations
import io.casperlabs.storage.dag.AncestorsStorage.Relation
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.shared.Log

class BaseBlockSpec
    extends FlatSpec
    with Matchers
    with Inspectors
    with HighwayFixture
    with HighwayNetworkFixture {

  behavior of "EraRuntime"

  it should "not participate in eras that aren't part of the main chain" in testFixture {
    implicit timer => implicit db =>
      new Fixture(
        length = 3 * eraDuration,
        initRoundExponent = 15 // ~ 8 hours
      ) (timer, db) {
        val thisProducer  = messageProducer
        val otherProducer = new MockMessageProducer[Task]("Bob")

        // 2 weeks for genesis, then 1 week for the descendants.
        // e0 - e1
        //    \ e2
        override def test =
          for {
            _  <- insertGenesis()
            e0 <- addGenesisEra()
            k1 <- e0.block(thisProducer, e0.keyBlockHash)
            b1 <- e0.block(thisProducer, k1)
            k2 <- e0.block(otherProducer, e0.keyBlockHash)

            // Set the fork choice to go towards k1.
            fc <- db.lookupUnsafe(b1)
            _  <- forkchoice.set(fc.asInstanceOf[Message.Block])

            _ <- EraRuntime.isOrphanEra[Task](k1) shouldBeF false
            _ <- EraRuntime.isOrphanEra[Task](k2) shouldBeF true

            // But the other validator doesn't see this and produces an era e2 on top of k2.
            // This validator should not produce messages in the era which it knows is not
            // on the path of the LFB.
            e1 <- e0.addChildEra(k1)
            e2 <- e0.addChildEra(k2)
            r1 <- makeRuntime(e1)
            r2 <- makeRuntime(e2)

            // This validator should participate in the era that is on the path of the LFB.
            _  = r1.isBonded shouldBe true
            a1 <- r1.initAgenda
            _  = a1 should not be empty

            // This validator should not have scheduled actions for the orphaned era.
            _  = r2.isBonded shouldBe false
            a2 <- r2.initAgenda
            _  = a2 shouldBe empty

          } yield {}
      }
  }

  it should "not join a longer streak of eras produced by an output-only validator" in testFixtures(
    validators = List("Alice", "Bob", "Charlie")
  ) { timer => validatorDatabases =>
    new NetworkFixture(validatorDatabases) {

      override val start = genesisEraStart

      // The hypotheses is that if Charlie builds a more isolated eras than the the lookback
      // of the fork choice then the other validators will join them:
      //
      // e0 - e1 - e2 - e3 - e4
      //    \
      //     e1' - e2' - e3' - e4'
      //
      override val length = eraDuration * 2 + eraDuration * 3 + eraDuration / 2

      override def makeRelayFixture(
          validator: String,
          db: SQLiteStorage.CombinedStorage[Task],
          supervisorsRef: Ref[Task, Map[String, EraSupervisor[Task]]],
          isSyncedRef: Ref[Task, Boolean]
      ) =
        new RelayFixture(
          length,
          validator = validator,
          initRoundExponent = 15, // ~ 8 hours
          supervisorsRef,
          isSyncedRef,
          printLevel = Log.Level.Warn
        ) (timer, db) {

          override val test = for {
            _           <- sleepUntil(start plus length)
            supervisors <- supervisorsRef.get
            supervisor  = supervisors(validator)
            activeEras  <- supervisor.activeEras
          } yield {
            activeEras should have size (1)
          }
        }
    }
  }
}
