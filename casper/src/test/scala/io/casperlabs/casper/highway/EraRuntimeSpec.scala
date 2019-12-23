package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.Clock
import com.google.protobuf.ByteString
import java.util.concurrent.TimeUnit
import io.casperlabs.casper.consensus.{Block, BlockSummary, Bond}
import io.casperlabs.casper.consensus.state
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import org.scalatest._
import scala.concurrent.duration._

class EraRuntimeSpec extends WordSpec with Matchers with Inspectors with TickUtils {
  import HighwayConf._
  import EraRuntime.Agenda

  val conf = HighwayConf(
    tickUnit = TimeUnit.MILLISECONDS,
    genesisEraStart = date(2019, 12, 9),
    eraDuration = EraDuration.FixedLength(days(7)),
    bookingDuration = days(10),
    entropyDuration = hours(3),
    postEraVotingDuration = VotingDuration.FixedLength(days(2))
  )

  val genesis = BlockSummary()
    .withBlockHash(ByteString.copyFromUtf8("genesis"))
    .withHeader(
      Block
        .Header()
        .withState(
          Block
            .GlobalState()
            .withBonds(
              List(
                Bond(validatorKey("Alice")).withStake(state.BigInt("3000")),
                Bond(validatorKey("Bob")).withStake(state.BigInt("4000")),
                Bond(validatorKey("Charlie")).withStake(state.BigInt("5000"))
              )
            )
        )
    )

  // Let's say we are right at the beginning of the era by default.
  implicit def defaultClock: Clock[Id] = new TestClock[Id](date(2019, 12, 9))

  def genesisEraRuntime(validator: Option[String] = none, roundExponent: Int = 0)(
      implicit C: Clock[Id]
  ) =
    EraRuntime.fromGenesis[Id](
      conf,
      genesis,
      validator.map(validatorKey),
      initRoundExponent = roundExponent
    )(Monad[Id], C)

  def validatorKey(name: String) =
    PublicKey(ByteString.copyFromUtf8(name))

  "EraRuntime" when {
    "started with the genesis block" should {
      val runtime =
        EraRuntime.fromGenesis[Id](conf, genesis, maybeValidatorId = none, initRoundExponent = 0)

      "use the genesis ticks for the era" in {
        conf.toInstant(Ticks(runtime.era.startTick)) shouldBe conf.genesisEraStart
        conf.toInstant(Ticks(runtime.era.endTick)) shouldBe conf.genesisEraEnd
      }

      "use the genesis block as key and booking block" in {
        runtime.era.keyBlockHash shouldBe genesis.blockHash
        runtime.era.bookingBlockHash shouldBe genesis.blockHash
        runtime.era.leaderSeed shouldBe ByteString.EMPTY
      }

      "not assign a parent era" in {
        runtime.era.parentKeyBlockHash shouldBe ByteString.EMPTY
      }

      "recognize booking block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isBookingBoundary(
            date(2019, 12, parentDay),
            date(2019, 12, blockDay)
          )
        runtime.bookingBoundaries should contain theSameElementsInOrderAs List(
          date(2019, 12, 13),
          date(2019, 12, 20)
        )
        check(4, 7) shouldBe false   // before the era
        check(11, 13) shouldBe true  // block falls on the first 10 day boundary
        check(13, 13) shouldBe false // parent and child on the same exact round, but parent was first
        check(13, 14) shouldBe false // parent block falls on the first 10 day boundary but the child does not
        check(19, 20) shouldBe true  // block falls on the second 10 day boundary
        check(25, 28) shouldBe false // after the era
      }

      "recognize key block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isKeyBoundary(
            date(2019, 12, parentDay),
            date(2019, 12, blockDay)
          )
        runtime.keyBoundaries should contain theSameElementsInOrderAs List(
          date(2019, 12, 13) plus hours(3),
          date(2019, 12, 20) plus hours(3)
        )
        check(11, 13) shouldBe false
        check(13, 14) shouldBe true
        check(14, 14) shouldBe false
        check(19, 20) shouldBe false
        check(20, 21) shouldBe true
        check(25, 28) shouldBe false
      }

      "recognize the switch block boundary" in {
        val end  = conf.genesisEraEnd
        val `1h` = hours(1)
        runtime.isSwitchBoundary(end minus `1h`, end plus `1h`) shouldBe true
        runtime.isSwitchBoundary(end minus `1h`, end) shouldBe true
        runtime.isSwitchBoundary(end, end) shouldBe false
        runtime.isSwitchBoundary(end, end plus `1h`) shouldBe false
      }
    }
  }

  "initAgenda" when {
    "the validator is bonded in the era" should {
      "schedule the first round" in {
        val runtime = genesisEraRuntime(validator = "Alice".some)

        val agenda = runtime.initAgenda
        agenda should have size 1
        agenda.head.tick shouldBe runtime.startTick
        agenda.head.action shouldBe Agenda.StartRound(runtime.startTick)
      }
      "schedule the first round at the next available round exponent" in {
        // Say this validator is starting a bit late.
        val now = conf.genesisEraStart plus 5.hours
        val exp = 10

        implicit val clock = new TestClock[Id](now)
        val runtime        = genesisEraRuntime(validator = "Alice".some, roundExponent = exp)

        val millisRound = math.pow(2.0, exp.toDouble).toLong
        val millisNow   = now.toEpochMilli
        val millisNext  = millisNow + (millisRound - millisNow % millisRound)

        val agenda = runtime.initAgenda
        agenda should have size 1
        agenda.head.tick shouldBe millisNext
        agenda.head.action shouldBe Agenda.StartRound(Ticks(millisNext))
      }
    }
    "the validator is not bonded in the era" should {
      "not schedule anything" in {
        genesisEraRuntime("Dave".some).initAgenda shouldBe empty
        genesisEraRuntime(none).initAgenda shouldBe empty
      }
    }
    "the era is already over" should {
      "not schedule anything" in {
        implicit val clock = new TestClock[Id](conf.genesisEraStart plus 700.days)
        genesisEraRuntime(validator = "Alice".some).initAgenda shouldBe empty
      }
    }
  }

  "handleMessage" when {
    "the validator is bonded" when {
      "given a lambda message" when {
        "it is from the leader" should {
          "create a lambda response" in (pending)
          "remember that a lambda message was received in this round" in (pending)
        }
        "it is not from the leader" should {
          "not respond" in (pending)
        }
        "it is a switch block" should {
          "create an era" in (pending)
        }
      }
      "given a ballot" should {
        "not respond" in (pending)
      }
    }
    "the validator is not bonded" when {
      "given a lambda message" should {
        "not respond" in (pending)
      }
      "given a switch block" should {
        "create an era" in (pending)
      }
    }
  }

  "handleAgenda" when {
    "starting a round" when {
      "in the active period of the era" when {
        "the validator is the leader" should {
          "create a lambda message" in (pending)
          "schedule another round" in (pending)
          "schedule an omega message" in (pending)
        }
        "the validator is not leading" should {
          "not create a lambda message" in (pending)
          "schedule another round" in (pending)
          "schedule an omega message" in (pending)
        }
      }
      "right after the era-end" when {
        "no previous switch block has been seen" should {
          "create a switch block" in (pending)
        }
        "already found a switch block" should {
          "only create ballots" in (pending)
        }
      }
      "in the post-era voting period" when {
        "the validator is the leader" should {
          "a ballot instead of a lambda message" in (pending)
        }
        "the voting is still going" should {
          "schedule an omega message" in (pending)
          "schedule another round" in (pending)
        }
        "the voting period is over" should {
          "not schedule anything" in (pending)
        }
      }
    }
  }
}
