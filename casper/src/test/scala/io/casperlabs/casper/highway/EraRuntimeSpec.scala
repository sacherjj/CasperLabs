package io.casperlabs.casper.highway

import java.util.concurrent.TimeUnit
import io.casperlabs.casper.consensus.BlockSummary
import org.scalatest._

class EraRuntimeConfSpec extends WordSpec with Matchers with TickUtils {
  import HighwayConf._

  val conf = HighwayConf(
    TimeUnit.MILLISECONDS,
    MilliTicks.date(2019, 12, 9),
    eraDuration = EraDuration.FixedLength(MilliTicks.days(7)),
    bookingTicks = MilliTicks.days(10),
    entropyTicks = MilliTicks.hours(3),
    VotingDuration.FixedLength(Ticks(0))
  )

  val genesis = BlockSummary()

  "EraRuntime" when {
    "started with the genesis block" should {
      val runtime = EraRuntime.fromGenesis(conf, genesis)

      "recognize booking block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isBookingBoundary(
            MilliTicks.date(2019, 12, parentDay),
            MilliTicks.date(2019, 12, blockDay)
          )
        runtime.bookingBoundaries should contain theSameElementsInOrderAs List(
          MilliTicks.date(2019, 12, 13),
          MilliTicks.date(2019, 12, 20)
        )
        check(4, 7) shouldBe false   // before the era
        check(11, 13) shouldBe true  // block falls on the first 10 day boundary
        check(13, 14) shouldBe false // parent block falls on the first 10 day boundary but the child does not
        check(19, 20) shouldBe true  // block falls on the second 10 day boundary
        check(25, 28) shouldBe false // after the era
      }

      "recognize key block boundaries" in {
        def check(parentDay: Int, blockDay: Int) =
          runtime.isKeyBoundary(
            MilliTicks.date(2019, 12, parentDay),
            MilliTicks.date(2019, 12, blockDay)
          )
        runtime.keyBoundaries should contain theSameElementsInOrderAs List(
          MilliTicks.date(2019, 12, 13) + MilliTicks.hours(3),
          MilliTicks.date(2019, 12, 20) + MilliTicks.hours(3)
        )
        check(11, 13) shouldBe false
        check(13, 14) shouldBe true
        check(19, 20) shouldBe false
        check(20, 21) shouldBe true
        check(25, 28) shouldBe false
      }
    }
  }

}
