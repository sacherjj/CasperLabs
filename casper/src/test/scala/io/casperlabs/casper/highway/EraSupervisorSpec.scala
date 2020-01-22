package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.{ContextShift, Resource, Sync, Timer}
import cats.effect.concurrent.{Ref}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond, Era}
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.helper.StorageFixture
import io.casperlabs.casper.highway.mocks.{MockForkChoice, MockMessageProducer}
import io.casperlabs.comm.gossiping.{Relaying, WaitHandle}
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.shared.{Log, LogStub}
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorageWriter
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.{BlockHash, SQLiteStorage}
import io.casperlabs.models.Message
import java.time.Instant
import java.util.concurrent.TimeUnit
import monix.catnap.SchedulerEffect
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.TestScheduler
import org.scalatest._
import scala.concurrent.duration._
import io.casperlabs.casper.PrettyPrinter

class EraSupervisorSpec extends FlatSpec with Matchers with Inspectors with StorageFixture {
  import EraSupervisorSpec._

  def testFixtures(numStorages: Int)(
      f: Timer[Task] => List[SQLiteStorage.CombinedStorage[Task]] => FixtureLike
  ): Unit = {
    val ctx   = TestScheduler()
    val timer = SchedulerEffect.timer[Task](ctx)
    withCombinedStorages(ctx, numStorages = numStorages) { dbs =>
      Task.async[Unit] { cb =>
        val fix = f(timer)(dbs)
        // TestScheduler allows us to manually forward time.
        // To get meaningful round IDs, we must start from the genesis.
        ctx.forwardTo(fix.start)
        // Without an extra delay the TestScheduler executes tasks immediately.
        fix.test.delayExecution(0.seconds).runAsync(cb)(ctx)
        // Now allow the tests to run forward until the end.
        ctx.forwardTo(fix.start plus fix.length)
        // There shouldn't be any uncaught exceptions.
        ctx.state.lastReportedError shouldBe null
      }
    }
  }

  def testFixture(f: Timer[Task] => SQLiteStorage.CombinedStorage[Task] => FixtureLike): Unit =
    testFixtures(numStorages = 1) { timer => dbs =>
      f(timer)(dbs.head)
    }

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

  it should "relay created messages to other nodes" in testFixtures(3) { implicit timer => dbs =>
    new FixtureLike {
      override val start  = genesisEraStart
      override val length = days(5)

      // Once created, store all supervisors here for relaying.
      @volatile var supervisors: Map[String, EraSupervisor[Task]] = Map.empty
      // Don't create messages until we add all supervisors to this collection,
      // otherwise they might miss some messages and there's no synchronizer here.
      val isSyncedRef: Ref[Task, Boolean] = Ref.unsafe(false)

      // Nested fixture, similar to a node, for a given validator, isolated from the others in this test.
      class RelayFixture(validator: String, db: SQLiteStorage.CombinedStorage[Task])
          extends Fixture(
            length,
            validator = validator,
            initRoundExponent = 15,
            isSyncedRef = isSyncedRef
          ) (timer, db) {

        val validatorId: PublicKeyBS              = validator
        val relayedRef: Ref[Task, Set[BlockHash]] = Ref.unsafe(Set.empty)

        override val relaying = new Relaying[Task] {
          override def relay(hashes: List[BlockHash]): Task[WaitHandle[Task]] =
            for {
              _      <- relayedRef.update(_ ++ hashes)
              blocks <- hashes.traverse(h => db.getBlockMessage(h)).map(_.flatten)
              _      = blocks should not be empty
              _ <- supervisors
                    .filterKeys(_ != validator)
                    .values
                    .toList
                    .traverse(s => blocks.traverse(b => s.validateAndAddBlock(b)))
            } yield ().pure[Task]
        }

        // Test a single validators point of view.
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

      // Create multiple fixtures, one for each validator all of which talk to each other.
      val fixtures = List("Alice", "Bob", "Charlie").zipWithIndex.map {
        case (validator, idx) => new RelayFixture(validator, dbs(idx))
      }

      // Override the main `FixtureLike` that's ultimately going to be executed by `testFixtures`.
      override def test = fixtures.traverse(_.makeSupervisor()).use { ss =>
        // Register the supervisors so they can all communicate.
        supervisors = ss.zipWithIndex.map {
          case (s, idx) => fixtures(idx).validator -> s
        }.toMap

        for {
          // Enable message production, now that the supervisors are registered.
          _ <- isSyncedRef.set(true)
          // Run individual tests. There could be common ones as well.
          _ <- fixtures.traverse(_.test)
        } yield ()
      }
    }
  }
}

object EraSupervisorSpec extends TickUtils with ArbitraryConsensus {

  import HighwayConf.{EraDuration, VotingDuration}

  val genesisEraStart       = date(2019, 12, 30)
  val eraDuration           = days(10)
  val postEraVotingDuration = days(2)

  val defaultConf = HighwayConf(
    tickUnit = TimeUnit.SECONDS,
    genesisEraStart = genesisEraStart,
    eraDuration = EraDuration.FixedLength(days(7)),
    bookingDuration = eraDuration,
    entropyDuration = hours(3),
    postEraVotingDuration = VotingDuration.FixedLength(postEraVotingDuration),
    omegaMessageTimeStart = 0.5,
    omegaMessageTimeEnd = 0.75
  )

  trait FixtureLike {
    // Where to set the TestScheduler to go from.
    def start: Instant
    // How long a time to simulate in the TestScheduler.
    def length: FiniteDuration
    // Override this value to make sure the assertions are executed.
    def test: Task[Unit]
  }

  abstract class Fixture(
      override val length: FiniteDuration,
      val conf: HighwayConf = defaultConf,
      val validator: String = "Alice",
      val initRoundExponent: Int = 0,
      val isSyncedRef: Ref[Task, Boolean] = Ref.unsafe(true),
      printLevel: Log.Level = Log.Level.Error
  )(
      implicit
      timer: Timer[Task],
      db: SQLiteStorage.CombinedStorage[Task]
  ) extends FixtureLike {

    override val start = conf.genesisEraStart

    implicit def `String => PublicKeyBS`(s: String): PublicKeyBS =
      PublicKey(ByteString.copyFromUtf8(s))

    val genesis = Message
      .fromBlockSummary(
        BlockSummary()
          .withBlockHash(ByteString.copyFromUtf8("genesis"))
          .withHeader(
            Block
              .Header()
              .withState(
                Block
                  .GlobalState()
                  .withBonds(
                    List(
                      Bond("Alice").withStake(state.BigInt("3000")),
                      Bond("Bob").withStake(state.BigInt("4000")),
                      Bond("Charlie").withStake(state.BigInt("5000"))
                    )
                  )
              )
          )
      )
      .get
      .asInstanceOf[Message.Block]

    implicit val log: Log[Task] with LogStub =
      LogStub[Task](
        prefix = validator.padTo(10, " ").mkString(""),
        printEnabled = true,
        printLevel = printLevel
      )

    implicit val forkchoice  = MockForkChoice.unsafe[Task](genesis)
    val maybeMessageProducer = new MockMessageProducer[Task](validator).some

    implicit val relaying = new Relaying[Task] {
      override def relay(hashes: List[BlockHash]): Task[WaitHandle[Task]] = ().pure[Task].pure[Task]
    }

    def addGenesisEra(): Task[Era] = {
      val era = EraRuntime.genesisEra(conf, genesis.blockSummary)
      db.addEra(era).as(era)
    }

    implicit class EraOps(era: Era) {
      def addChildEra(): Task[Era] = {
        val nextEndTick = conf.toTicks(conf.eraEnd(conf.toInstant(Ticks(era.endTick))))
        val child = sample[Era]
          .withParentKeyBlockHash(era.keyBlockHash)
          .withStartTick(era.endTick)
          .withEndTick(nextEndTick)
          .withBonds(era.bonds)
        db.addEra(child).as(child)
      }
    }

    def makeRuntime(era: Era): Task[EraRuntime[Task]] =
      EraRuntime.fromEra[Task](
        conf,
        era,
        maybeMessageProducer,
        initRoundExponent,
        isSyncedRef.get
      )

    def makeSupervisor(): Resource[Task, EraSupervisor[Task]] =
      for {
        _ <- Resource.liftF {
              val genesisSummary = genesis.blockSummary
              val genesisBlock = Block(
                blockHash = genesisSummary.blockHash,
                header = genesisSummary.header,
                signature = genesisSummary.signature
              )
              db.put(BlockMsgWithTransform().withBlockMessage(genesisBlock))
            }
        supervisor <- EraSupervisor[Task](
                       conf,
                       genesis.blockSummary,
                       maybeMessageProducer,
                       initRoundExponent,
                       isSyncedRef.get
                     )
      } yield supervisor

    /** Use this to wait for future conditions in the test, don't try to use `.tick()`
      * because that can only be called outside the test's for comprehension and it's
      * done at the top. But sleeps will schedule the tasks that tell the TestScheduler
      * when the next job is due.
      */
    def sleepUntil(t: Instant): Task[Unit] =
      for {
        now   <- Timer[Task].clock.realTime(conf.tickUnit)
        later = conf.toTicks(t)
        delay = math.max(later - now, 0L)
        _     <- Timer[Task].sleep(FiniteDuration(delay, conf.tickUnit))
      } yield ()
  }

}
