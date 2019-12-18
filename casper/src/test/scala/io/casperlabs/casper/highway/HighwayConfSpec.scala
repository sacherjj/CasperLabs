package io.casperlabs.casper.highway

import java.util.concurrent.TimeUnit
import org.scalatest._

class HighwayConfSpec extends WordSpec with Matchers with TickUtils {
  import HighwayConf._

  val init = HighwayConf(
    TimeUnit.MILLISECONDS,
    Ticks(0),
    EraDuration.FixedLength(Ticks(0)),
    Ticks(0),
    Ticks(0),
    VotingDuration.FixedLength(Ticks(0))
  )

  "eraEndTick" when {
    // There was a leap second announced for 2008 Dec 31; that's surely included in JDK8.

    "eraDuration is given as Ticks" should {
      "add the specified number of ticks" in {
        val conf = init.copy(
          tickUnit = TimeUnit.SECONDS,
          eraDuration = EraDuration.FixedLength(Ticks(7 * 24 * 60 * 60))
        )

        // Start from the previous Monday midnight.
        val startTick = conf.toTicks(dateTimestamp(2008, 12, 29))
        val endTick   = conf.eraEndTick(startTick)

        // The GregorianCalendar doesn't deal with leap seconds,
        // while the LocalDateTime spreads it around; we should
        // not see any discrepancy.
        endTick shouldBe dateTimestamp(2009, 1, 5) / 1000
      }
    }

    "eraDuration is given as Calendar" should {
      "add a specified calendar unit" in {
        val conf = init.copy(
          eraDuration = EraDuration.Calendar(3, EraDuration.CalendarUnit.MONTHS)
        )
        val startTick = conf.toTicks(dateTimestamp(2008, 12, 1))
        val endTick   = conf.eraEndTick(startTick)
        endTick shouldBe MilliTicks.date(2009, 3, 1)
      }
    }
  }

  "genesisEraEndTick" should {
    "expand the genesis era to produce multiple booking blocks" in {
      val conf = init.copy(
        genesisEraStartTick = MilliTicks.date(2019, 12, 16),
        bookingTicks = MilliTicks.days(10),
        eraDuration = EraDuration.FixedLength(MilliTicks.days(7))
      )
      conf.genesisEraEndTick shouldBe MilliTicks.date(2019, 12, 30)
    }
  }

  "criticalBoundaries" should {
    "collect all booking block ticks for the genesis era" in {
      val conf = init.copy(
        genesisEraStartTick = MilliTicks.date(2019, 12, 2),
        bookingTicks = MilliTicks.days(18),
        eraDuration = EraDuration.FixedLength(MilliTicks.days(7))
      )
      val boundaries = conf.criticalBoundaries(
        conf.genesisEraStartTick,
        conf.genesisEraEndTick,
        conf.bookingTicks
      )

      boundaries should contain theSameElementsInOrderAs List(
        MilliTicks.date(2019, 12, 5),
        MilliTicks.date(2019, 12, 12),
        MilliTicks.date(2019, 12, 19)
      )
    }

    "collect just the single key tick for a normal era" in {
      val conf = init.copy(
        bookingTicks = MilliTicks.days(10),
        entropyTicks = MilliTicks.hours(2),
        eraDuration = EraDuration.FixedLength(MilliTicks.days(7))
      )
      val boundaries = conf.criticalBoundaries(
        MilliTicks.date(2019, 12, 23),
        MilliTicks.date(2019, 12, 30),
        conf.keyTicks
      )

      boundaries should contain only (
        MilliTicks.date(2019, 12, 27) + MilliTicks.hours(2)
      )
    }
  }
}
