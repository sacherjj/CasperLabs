package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.{ContextShift, Resource, Sync, Timer}
import cats.effect.concurrent.{Ref}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond, Era}
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.helper.StorageFixture
import io.casperlabs.comm.gossiping.{Relaying, WaitHandle}
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.shared.{Log, LogStub}
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorageWriter
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.models.Message
import java.time.Instant
import java.util.concurrent.TimeUnit
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest._
import scala.concurrent.duration._
import io.casperlabs.casper.PrettyPrinter

class EraSupervisorSpec extends FlatSpec with Matchers with Inspectors with HighwayFixture {

  behavior of "collectActiveEras"

  it should "collect voting and active eras" in testFixture { implicit timer => implicit db =>
    new Fixture(length = 6 * eraDuration) {
      // 2 weeks for genesis, then 1 week for each descendant.
      // e0 - e1
      //    \ e2 - e3
      //         \ e4 - e5
      override def test =
        for {
          e0 <- addGenesisEra()
          _  <- e0.addChildEra()
          e2 <- e0.addChildEra()
          e3 <- e2.addChildEra()
          e4 <- e2.addChildEra()
          e5 <- e4.addChildEra()
          // Wait until where e3 and e4 are voting.
          // Their parent eras will have their voting over, and
          // their children should be active.
          _      <- sleepUntil(conf.toInstant(Ticks(e3.endTick)) plus postEraVotingDuration / 2)
          active <- EraSupervisor.collectActiveEras[Task](makeRuntime)
        } yield {
          active.map(_._1.era) should contain theSameElementsAs List(e3, e4, e5)
        }
    }
  }

  behavior of "EraSupervisor"

  it should "start running the genesis era and create descendant eras when the time comes" in testFixture {
    implicit timer => implicit db =>
      new Fixture(
        length = eraDuration * 2 + postEraVotingDuration,
        initRoundExponent = 15 // ~ 8 hours; so we don't get that many blocks,
      ) {
        override def test =
          makeSupervisor().use { supervisor =>
            for {
              _    <- Task.sleep(eraDuration * 2 + postEraVotingDuration / 2)
              eras <- supervisor.eras
            } yield {
              eras should have size 2
            }
          }
      }
  }

  it should "relay created messages to other nodes" in testFixtures(
    validators = List("Alice", "Bob", "Charlie")
  ) { implicit timer => validatorDatabases =>
    new FixtureLike {
      override val start  = genesisEraStart
      override val length = days(5)

      class RelayFixture(
          validator: String,
          db: SQLiteStorage.CombinedStorage[Task],
          supervisorsRef: Ref[Task, Map[String, EraSupervisor[Task]]],
          isSyncedRef: Ref[Task, Boolean]
      ) extends Fixture(
            length,
            validator = validator,
            initRoundExponent = 15,
            isSyncedRef = isSyncedRef
          ) (timer, db) {

        val validatorId: PublicKeyBS              = validator
        val relayedRef: Ref[Task, Set[BlockHash]] = Ref.unsafe(Set.empty)

        override lazy val relaying = new Relaying[Task] {
          override def relay(hashes: List[BlockHash]): Task[WaitHandle[Task]] =
            for {
              _           <- relayedRef.update(_ ++ hashes)
              blocks      <- hashes.traverse(h => db.getBlockMessage(h)).map(_.flatten)
              _           = blocks should not be empty
              supervisors <- supervisorsRef.get
              // Notify other supervisors.
              _ <- supervisors
                    .filterKeys(_ != validator)
                    .values
                    .toList
                    .traverse(s => blocks.traverse(b => s.validateAndAddBlock(b)))
            } yield ().pure[Task]
        }

        override val test = for {
          _        <- sleepUntil(start plus length)
          relayed  <- relayedRef.get
          dag      <- db.getRepresentation
          messages <- relayed.toList.traverse(dag.lookupUnsafe)
          parents  <- messages.traverse(m => dag.lookupUnsafe(m.parentBlock))
        } yield {
          messages should not be empty
          atLeast(1, messages) shouldBe a[Message.Ballot]
          atLeast(1, messages) shouldBe a[Message.Block]

          // Validators should only try to relay their own messages.
          forAll(messages) { m =>
            m.validatorId shouldBe validatorId
          }
          // There should be some responses to other validators' messages.
          forAtLeast(1, parents) { p =>
            p.validatorId should not be empty
            p.validatorId should not be validatorId
          }
        }
      }

      val network =
        for {
          // Don't create messages until we add all supervisors to this collection,
          // otherwise they might miss some messages and there's no synchronizer here.
          isSyncedRef    <- Resource.liftF(Ref[Task].of(false))
          supervisorsRef <- Resource.liftF(Ref[Task].of(Map.empty[String, EraSupervisor[Task]]))
          fixtures = validatorDatabases.map {
            case (validator, db) =>
              new RelayFixture(validator, db, supervisorsRef, isSyncedRef)
          }
          validators  = validatorDatabases.unzip._1
          supervisors <- fixtures.traverse(_.makeSupervisor())
          _ <- Resource.liftF {
                supervisorsRef.set(validators zip supervisors toMap) *> isSyncedRef.set(true)
              }
        } yield fixtures

      override def test: Task[Unit] =
        network.use(_.traverse(_.test).void)
    }
  }
}
