package io.casperlabs.casper.highway

import cats.Id
import com.google.protobuf.ByteString
import java.util.concurrent.TimeUnit
import io.casperlabs.casper.consensus.BlockSummary
import org.scalatest._

class EraRuntimeSpec extends WordSpec with Matchers with TickUtils {
  import HighwayConf._

  val conf = HighwayConf(
    tickUnit = TimeUnit.MILLISECONDS,
    genesisEraStart = date(2019, 12, 9),
    eraDuration = EraDuration.FixedLength(days(7)),
    bookingDuration = days(10),
    entropyDuration = hours(3),
    postEraVotingDuration = VotingDuration.FixedLength(days(2))
  )

  val genesis = BlockSummary().withBlockHash(ByteString.copyFromUtf8("genesis"))

  "EraRuntime" when {
    "started with the genesis block" should {
      val runtime = EraRuntime.fromGenesis[Id](conf, genesis)

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
      "schedule the first round" in (pending)
    }
    "the validator is not bonded in the era" should {
      "not schedule anything" in (pending)
    }
    "the era is already over" should {
      "not schedule anything" in (pending)
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
