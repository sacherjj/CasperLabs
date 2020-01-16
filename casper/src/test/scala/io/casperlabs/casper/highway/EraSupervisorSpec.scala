package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.Sync
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond, Era}
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.helper.StorageFixture
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.storage.SQLiteStorage
import io.casperlabs.models.Message
import io.casperlabs.casper.highway.mocks.{MockForkChoice, MockMessageProducer}
import java.time.Instant
import java.util.concurrent.TimeUnit
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest._
import scala.concurrent.duration._

class EraSupervisorSpec extends FlatSpec with Matchers with StorageFixture {
  import EraSupervisorSpec._
  import Scheduler.Implicits.global

  def testFixture(f: SQLiteStorage.CombinedStorage[Task] => Fixture) =
    withCombinedStorage { db =>
      f(db).test
    }

  behavior of "collectActiveEras"

  it should "collect voting and active eras" in testFixture { implicit db =>
    new Fixture() {
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
          // Set the clock forward to where e3 and e4 is voting.
          // Their parent eras will have their voting over, and
          // their children should be active.
          _      <- clock.set(conf.toInstant(Ticks(e3.endTick)) plus 1.hour)
          active <- EraSupervisor.collectActiveEras[Task](makeRuntime)
        } yield {
          active.map(_._1.era) should contain theSameElementsAs List(e3, e4, e5)
        }
    }
  }
}

object EraSupervisorSpec extends TickUtils with ArbitraryConsensus {

  import HighwayConf.{EraDuration, VotingDuration}

  val defaultConf = HighwayConf(
    tickUnit = TimeUnit.SECONDS,
    genesisEraStart = date(2019, 12, 30),
    eraDuration = EraDuration.FixedLength(days(7)),
    bookingDuration = days(10),
    entropyDuration = hours(3),
    postEraVotingDuration = VotingDuration.FixedLength(days(2)),
    omegaMessageTimeStart = 0.5,
    omegaMessageTimeEnd = 0.75
  )

  abstract class Fixture(val conf: HighwayConf = defaultConf)(
      implicit db: SQLiteStorage.CombinedStorage[Task],
      scheduler: Scheduler
  ) {
    // Override this value to make sure the assertions are executed.
    def test: Task[Unit]

    implicit def `String => PublicKeyBS`(s: String) = PublicKey(ByteString.copyFromUtf8(s))

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

    val validator = "Alice"

    implicit val clock      = TestClock.adjustable[Task](conf.genesisEraStart)
    implicit val forkchoice = MockForkChoice[Task](genesis).runSyncUnsafe(5.seconds)

    def addGenesisEra(): Task[Era] = {
      val era = Era(
        keyBlockHash = genesis.messageHash,
        bookingBlockHash = genesis.messageHash,
        startTick = conf.toTicks(conf.genesisEraStart),
        endTick = conf.toTicks(conf.genesisEraEnd),
        bonds = genesis.blockSummary.getHeader.getState.bonds
      )
      db.addEra(era).as(era)
    }

    implicit class EraOps(era: Era) {
      def addChildEra(): Task[Era] = {
        val nextEnd = conf.toTicks(conf.eraEnd(conf.toInstant(Ticks(era.endTick))))
        val child = sample[Era]
          .withParentKeyBlockHash(era.keyBlockHash)
          .withStartTick(era.endTick)
          .withEndTick(nextEnd)
          .withBonds(era.bonds)
        db.addEra(child).as(child)
      }
    }

    def makeRuntime(era: Era): Task[EraRuntime[Task]] =
      EraRuntime.fromEra[Task](
        conf,
        era,
        maybeMessageProducer = new MockMessageProducer[Task](validator).some,
        initRoundExponent = 0,
        isSynced = true.pure[Task]
      )

  }
}
