package io.casperlabs.casper.highway

import java.util.{Calendar, Date}
import java.util.concurrent.TimeUnit
import org.scalatest._

class HighwayConfSpec extends WordSpec with Matchers {
  import HighwayConf._

  val init = HighwayConf(
    TimeUnit.MILLISECONDS,
    Ticks(0),
    EraDuration.FixedLength(Ticks(0)),
    Ticks(0),
    Ticks(0),
    VotingDuration.FixedLength(Ticks(0))
  )

  def dateMillis(y: Int, m: Int, d: Int): Timestamp = {
    val c = Calendar.getInstance
    c.clear()
    c.set(y, m - 1, d)
    Timestamp(c.getTimeInMillis)
  }

  "eraEndTick" when {
    // There was a leap second announced for 2008 Dec 31; that's surely included in JDK8.

    "eraDuration is given as Ticks" should {
      "add the specified number of ticks" in {
        val conf = init.copy(
          tickUnit = TimeUnit.SECONDS,
          eraDuration = EraDuration.FixedLength(Ticks(7 * 24 * 60 * 60))
        )

        // Start from the previous Monday midnight.
        val startTick = conf.toTicks(dateMillis(2008, 12, 29))
        val endTick   = conf.eraEndTick(startTick)

        // The GregorianCalendar doesn't deal with leap seconds,
        // while the LocalDateTime spreads it around; we should
        // not see any discrepancy.
        endTick shouldBe dateMillis(2009, 1, 5) / 1000
      }
    }

    "eraDuration is given as Calendar" should {
      "add a specified calendar unit" in {
        val conf = init.copy(
          eraDuration = EraDuration.Calendar(3, EraDuration.CalendarUnit.MONTHS)
        )
        val startTick = conf.toTicks(dateMillis(2008, 12, 1))
        val endTick   = conf.eraEndTick(startTick)
        endTick shouldBe dateMillis(2009, 3, 1)
      }
    }
  }

  "genesisEraEndtick" should {
    "expand the genesis era to produce multiple booking blocks" in {
      val conf = init.copy(
        genesisEraStartTick = Ticks(dateMillis(2019, 12, 16)),
        bookingTicks = Ticks(10 * 24 * 60 * 60 * 1000),
        eraDuration = EraDuration.FixedLength(Ticks(7 * 24 * 60 * 60 * 1000))
      )
      conf.genesisEraEndTick shouldBe dateMillis(2019, 12, 30)
    }
  }
}
