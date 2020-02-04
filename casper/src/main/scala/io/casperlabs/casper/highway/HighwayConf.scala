package io.casperlabs.casper.highway

import java.util.concurrent.TimeUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.concurrent.duration._

final case class HighwayConf(
    /** Real-world time unit for one tick. */
    tickUnit: TimeUnit,
    /** Starting tick for the genesis era. */
    genesisEraStart: Instant,
    /** Method for calculating the end of the era based on the start tick. */
    eraDuration: HighwayConf.EraDuration,
    /** Amount of time to go back before the start of the era for picking the booking block. */
    bookingDuration: FiniteDuration,
    /** Amount of time to wait after the booking before we pick the key block, collecting the magic bits along the way. */
    entropyDuration: FiniteDuration,
    /** Stopping condition for producing ballots after the end of the era. */
    postEraVotingDuration: HighwayConf.VotingDuration,
    /** Fraction of time through the round after which we can create an omega message. */
    omegaMessageTimeStart: Double,
    /** Fraction of time through the round before which we must have created the omega message. */
    omegaMessageTimeEnd: Double
) {
  import HighwayConf._

  /** Time to go back before the start of the era for picking the key block. */
  def keyDuration: FiniteDuration =
    bookingDuration minus entropyDuration

  /** Convert a `timeUnit` specific (it's up to the caller to make sure this is compatible)
    * number of ticks since the Unix epch to an instant in time.
    */
  def toInstant(t: Ticks): Instant = {
    val d = FiniteDuration(t, tickUnit)
    Instant.ofEpochSecond(0) plus d
  }

  /** Convert an instant in time to the number of ticks we can store in the era model,
    * or assign to a round ID in a block.
    */
  def toTicks(t: Instant): Ticks = {
    val s = tickUnit.convert(t.getEpochSecond, TimeUnit.SECONDS)
    val n = tickUnit.convert(t.getNano.toLong, TimeUnit.NANOSECONDS)
    Ticks(s + n)
  }

  private def eraEnd(start: Instant, duration: EraDuration): Instant = {
    import EraDuration.CalendarUnit._
    val UTC = ZoneId.of("UTC")

    duration match {
      case EraDuration.FixedLength(ticks) =>
        start plus ticks

      case EraDuration.Calendar(length, unit) =>
        val s = LocalDateTime.ofInstant(start, UTC)
        val e = unit match {
          case DAYS   => s.plusDays(length)
          case WEEKS  => s.plusWeeks(length)
          case MONTHS => s.plusMonths(length)
          case YEARS  => s.plusYears(length)
        }
        e.atZone(UTC).toInstant
    }
  }

  /** Calculate the era end tick based on a start tick. */
  def eraEnd(start: Instant): Instant =
    eraEnd(start, eraDuration)

  /** The booking block is picked from a previous era, e.g. with 7 day eras
    * we look for the booking block 10 days before the era start, so there's
    * an extra era before the one with the booking block and the one where
    * that block becomes effective. This gives humans a week to correct any
    * problems in case there's no unique key block and booking block to use.
    *
    * However the second era, the one following genesis, won't have one before
    * genesis to look at, so the genesis era has to be longer to produce many
    * booking blocks, one for era 2, and one for era 3.
    */
  def genesisEraEnd: Instant = {
    val endTick    = eraEnd(genesisEraStart)
    val length     = endTick.toEpochMilli - genesisEraStart.toEpochMilli
    val multiplier = 1 + bookingDuration.toMillis / length
    (1 until multiplier.toInt).foldLeft(endTick)((t, _) => eraEnd(t))
  }

  /** Any time we create a block it may have to be a booking block,
    * in which case we have to execute the auction. There will be
    * exactly one booking boundary per era, except in the genesis
    * which has more, so that multiple following eras can find
    * booking blocks in it.
    *
    * Given a start and end tick for any particular era E,
    * the function returns the list of ticks that are at a
    * certain delay before the start of another era coming after E.
    *
    * This handles eras of different lengths: it filters out the upcoming
    * boundaries so that they fall between start and end.
    *
    * For example, if the Genesis era was chosen to be 3 weeks long,
    * we'd see the following booking blocks (B) appear; so Genesis would have 2,
    * and E1 would have just 1, used by E3.
    *  G : |....................|
    *  E1:            B         |......|
    *  E2:                   B         |......|
    *  E3:                          B         |......|
    */
  def criticalBoundaries(
      start: Instant,
      end: Instant,
      delayDuration: FiniteDuration
  ): List[Instant] = {
    def loop(acc: List[Instant], nextStart: Instant): List[Instant] = {
      val boundary = nextStart minus delayDuration
      if (boundary isBefore start) loop(acc, eraEnd(nextStart))
      else if (boundary isBefore end) loop(boundary :: acc, eraEnd(nextStart))
      else acc
    }
    loop(Nil, end).reverse
  }
}

object HighwayConf {

  /** By default we want eras to start on Sunday Midnight.
    * We want to be able to calculate the end of an era when we start it.
    */
  sealed trait EraDuration
  object EraDuration {

    /** Fixed length eras are easy to deal with, but over the years can accumulate leap seconds
      * which means they will eventually move away from starting at the desired midnight.
      * In practice this shouldn't be a problem with the Java time library because it spreads
      * leap seconds throughout the day so that they appear to be exactly 86400 seconds.
      */
    case class FixedLength(duration: FiniteDuration) extends EraDuration

    /** Fixed endings can be calculated with the calendar, to make eras take exactly one week (or a month),
      * but it means eras might have different lengths. Using this might mean that a different platform
      * which handles leap seconds differently could assign different tick IDs.
      *
      * In practice at least the JVM doesn't make leap seconds visible, they get distributed over the last day of the year.
      */
    case class Calendar(length: Long, unit: CalendarUnit) extends EraDuration

    sealed trait CalendarUnit
    object CalendarUnit {
      case object DAYS   extends CalendarUnit
      case object WEEKS  extends CalendarUnit
      case object MONTHS extends CalendarUnit
      case object YEARS  extends CalendarUnit
    }
  }

  /** Describe how long after the end of the era validator need to keep producing ballots to finalize the last few blocks. */
  sealed trait VotingDuration
  object VotingDuration {

    /** Produce ballots according to the leader schedule for up to a certain number of ticks, e.g. 2 days. */
    case class FixedLength(duration: FiniteDuration) extends VotingDuration

    /** Produce ballots until a certain level of summits are achieved on top of the switch blocks. */
    case class SummitLevel(k: Int) extends VotingDuration
  }
}
