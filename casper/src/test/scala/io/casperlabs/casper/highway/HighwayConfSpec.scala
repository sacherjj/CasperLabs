package io.casperlabs.casper.highway

import java.time.Instant
import java.util.concurrent.TimeUnit
import org.scalatest._

class HighwayConfSpec extends WordSpec with Matchers with TickUtils {
  import HighwayConf._

  val Zero = hours(0)

  val init = HighwayConf(
    genesisEraStart = date(2000, 1, 3),
    tickUnit = TimeUnit.MILLISECONDS,
    eraDuration = EraDuration.FixedLength(Zero),
    bookingDuration = Zero,
    entropyDuration = Zero,
    postEraVotingDuration = VotingDuration.FixedLength(Zero),
    omegaMessageTimeStart = 0.0,
    omegaMessageTimeEnd = 0.0,
    omegaBlocksEnabled = false
  )

  "toTicks" should {
    "convert to the number of ticks since epoch" in {
      init.toTicks(init.genesisEraStart) shouldBe Ticks(dateTimestamp(2000, 1, 3))
    }
  }

  "toInstant" should {
    "convert ticks to insants" in {
      val a = Instant.ofEpochMilli(System.currentTimeMillis)
      val b = init.toTicks(a)
      val c = init.toInstant(b)
      c shouldBe a
    }
  }

  "eraEnd" when {
    // There was a leap second announced for 2008 Dec 31; that's surely included in JDK8.

    "eraDuration is given as Ticks" should {
      "add the specified number of ticks" in {
        val conf = init.copy(
          tickUnit = TimeUnit.SECONDS,
          eraDuration = EraDuration.FixedLength(days(7))
        )

        // Start from the previous Monday midnight.
        val startTick = date(2008, 12, 29)
        val endTick   = conf.eraEnd(startTick)

        // The GregorianCalendar doesn't deal with leap seconds,
        // while the LocalDateTime spreads it around; we should
        // not see any discrepancy.
        endTick shouldBe date(2009, 1, 5)

        conf.toTicks(endTick) shouldBe dateTimestamp(2009, 1, 5) / 1000
      }
    }

    "eraDuration is given as Calendar" should {
      "add a specified calendar unit" in {
        val conf = init.copy(
          eraDuration = EraDuration.Calendar(3, EraDuration.CalendarUnit.MONTHS)
        )
        val startTick = date(2008, 12, 1)
        val endTick   = conf.eraEnd(startTick)
        endTick shouldBe date(2009, 3, 1)
      }
    }
  }

  "genesisEraEnd" should {
    "expand the genesis era to produce multiple booking blocks" in {
      val conf = init.copy(
        genesisEraStart = date(2019, 12, 16),
        bookingDuration = days(10),
        eraDuration = EraDuration.FixedLength(days(7))
      )
      conf.genesisEraEnd shouldBe date(2019, 12, 30)
    }
  }

  "criticalBoundaries" should {
    "collect all booking block ticks for the genesis era" in {
      val conf = init.copy(
        genesisEraStart = date(2019, 12, 2),
        bookingDuration = days(18),
        eraDuration = EraDuration.FixedLength(days(7))
      )
      val boundaries = conf.criticalBoundaries(
        conf.genesisEraStart,
        conf.genesisEraEnd,
        conf.bookingDuration
      )

      boundaries should contain theSameElementsInOrderAs List(
        date(2019, 12, 5),
        date(2019, 12, 12),
        date(2019, 12, 19)
      )
    }

    "collect just the single key tick for a normal era" in {
      val conf = init.copy(
        bookingDuration = days(10),
        entropyDuration = hours(2),
        eraDuration = EraDuration.FixedLength(days(7))
      )
      val boundaries = conf.criticalBoundaries(
        date(2019, 12, 23),
        date(2019, 12, 30),
        conf.keyDuration
      )

      boundaries should contain only (
        date(2019, 12, 27) plus hours(2)
      )
    }
  }
}
