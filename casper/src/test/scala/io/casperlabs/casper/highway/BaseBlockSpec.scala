package io.casperlabs.casper.highway

import cats.implicits._
import cats.effect.concurrent.Ref
import monix.eval.Task
import org.scalatest._
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import io.casperlabs.casper.highway.mocks.{MockForkChoice, MockMessageProducer}
import io.casperlabs.casper.consensus.{Bond, Era}
import io.casperlabs.casper.consensus.state
import io.casperlabs.models.Message
import io.casperlabs.casper.dag.DagOperations
import io.casperlabs.storage.dag.AncestorsStorage.Relation
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.shared.Log
import io.casperlabs.shared.ByteStringPrettyPrinter.byteStringShow

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

  // NOTE: This test is ignored because once there's an isolated validator it looks like everyone's
  // eras diverge, yet there are no duplicates. Maybe it's a limitation of the test scheduler and the fibers,
  // but it's difficult to figure out why the 3 connected validators disagree. Also saw some validators
  // running out of eras completely, but switching to summit based finality yielded just 2 eras instead of 4+.
  it should "not join a longer streak of eras produced by an output-only validator" ignore testFixtures(
    validators = List("Alice", "Bob", "Charlie", "Dave"),
    timeout = 30.seconds
  ) { timer => validatorDatabases =>
    new NetworkFixture(validatorDatabases) {

      override val start = genesisEraStart

      val conf = defaultConf.copy(
        postEraVotingDuration = HighwayConf.VotingDuration.FixedLength(postEraVotingDuration)
      )

      // The hypotheses is that if Alice builds more isolated eras than the the lookback
      // of the fork choice then the other validators will join them:
      //
      // e0 - e1 - e2 - e3 - e4
      //    \
      //     e1' - e2' - e3' - e4'
      //
      override val length = eraDuration * 8

      val validators          = validatorDatabases.unzip._1.toSet
      val normalValidator     = "Alice"
      val outputOnlyValidator = "Bob"
      val routing = validators.map { v =>
        val peers = if (v == outputOnlyValidator) validators else validators - outputOnlyValidator
        v -> peers
      }.toMap

      override def makeRelayFixture(
          validator: String,
          db: SQLiteStorage.CombinedStorage[Task],
          supervisorsRef: Ref[Task, Map[String, EraSupervisor[Task]]],
          isSyncedRef: Ref[Task, Boolean]
      ) =
        new RelayFixture(
          length,
          validator = validator,
          // Low enough exponent so that the isolated validator can keep producing eras.
          initRoundExponent = 14,
          supervisorsRef,
          isSyncedRef,
          conf = conf,
          routing = routing,
          printLevel = Log.Level.Warn
        ) (timer, db) {

          override def bonds: List[Bond] = List(
            Bond("Alice").withStake(state.BigInt("3000")),
            Bond("Bob").withStake(state.BigInt("4000")),
            Bond("Charlie").withStake(state.BigInt("5000")),
            Bond("Dave").withStake(state.BigInt("5000"))
          )

          override val test = for {
            _           <- sleepUntil(start plus length)
            supervisors <- supervisorsRef.get
            activeEras <- validators.toList
                           .traverse { v =>
                             supervisors(v).activeEras.map(v -> _.map(_.keyBlockHash))
                           }
                           .map(_.toMap)
            eras <- if (activeEras(validator).isEmpty) Task.now(Nil)
                   else eraTree(activeEras(validator).head)
          } yield {
            println(
              s"$validator: ${activeEras(validator).map(_.show).mkString(", ")} "
            )
            println(s"era tree: ${eras.map(_.keyBlockHash.show).mkString(", ")}")
            activeEras(validator) should have size (1)
            if (validator == outputOnlyValidator) {
              activeEras(validator) should not be activeEras(normalValidator)
            } else {
              activeEras(validator) shouldBe activeEras(normalValidator)
            }
          }

          def eraTree(keyBlockHash: BlockHash) = {
            def loop(keyBlockHash: BlockHash, eras: List[Era]): Task[List[Era]] =
              db.getEraUnsafe(keyBlockHash).flatMap { era =>
                if (era.parentKeyBlockHash.isEmpty) Task.now(era :: eras)
                else loop(era.parentKeyBlockHash, era :: eras)
              }
            loop(keyBlockHash, Nil)
          }
        }

    }
  }
}
