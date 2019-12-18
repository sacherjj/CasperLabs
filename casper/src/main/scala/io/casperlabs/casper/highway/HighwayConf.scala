package io.casperlabs.casper.highway

import java.util.concurrent.TimeUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.concurrent.duration._

final case class HighwayConf(
    /** Real-world time unit for one tick. */
    tickUnit: TimeUnit,
    /** Starting tick for the genesis era, measured in ticks since the Unix epoch. */
    genesisEraStartTick: Tick,
    /** Method for calculating the end of the era based on the start tick. */
    eraDuration: HighwayConf.EraDuration,
    /** Number of ticks to go back before the start of the era for picking the booking block. */
    bookingBlockTicks: Tick,
    /** Number of ticks after the booking before we pick the key block, collecting the magic bits along the way. */
    entropyTicks: Tick,
    /** Stopping condition for producing ballots after the end of the era. */
    postEraVotingDuration: HighwayConf.VotingDuration
)

object HighwayConf {

  /** By default we want eras to start on Sunday Midnight.
    * We want to be able to calculate the end of an era when we start it.
    */
  sealed trait EraDuration
  object EraDuration {

    /** Fixed length eras are easy to deal with, but over the years can accumulate leap seconds
      * which menas they will eventually move away from starting at the desired midnight.
      * In practice this shouldn't be a problem with the Java time library because it spreads
      * leap seconds throughout the day so that they appear to be exactly 86400 seconds.
      */
    case class Ticks(ticks: Tick) extends EraDuration

    /** Fixed endings can be calculated with the calendar, to make eras take exactly one week (or a month),
      * but it means eras might have different lengths.
      */
    case class Calendar(length: Long, unit: CalendarUnit) extends EraDuration

    sealed trait CalendarUnit
    object CalendarUnit {
      case object SECONDS extends CalendarUnit
      case object MINUTES extends CalendarUnit
      case object HOURS   extends CalendarUnit
      case object DAYS    extends CalendarUnit
      case object WEEKS   extends CalendarUnit
      case object MONTHS  extends CalendarUnit
      case object YEARS   extends CalendarUnit
    }
  }

  /** Describe how long after the end of the era validator need to keep producing ballots to finalize the last few blocks. */
  sealed trait VotingDuration
  object VotingDuration {

    /** Produce ballots according to the leader schedule for up to a certain number of ticks, e.g. 2 days. */
    case class Ticks(ticks: Tick) extends VotingDuration

    /** Produce ballots until a certain level of summits are achieved on top of the switch blocks. */
    case class SummitLevel(k: Int) extends VotingDuration
  }

  implicit class Ops(val conf: HighwayConf) extends AnyVal {

    def toTimestamp(t: Tick): Timestamp =
      Timestamp(conf.tickUnit.toMillis(t))

    def toTicks(t: Timestamp): Tick =
      Tick(conf.tickUnit.convert(t, TimeUnit.MILLISECONDS))

    def eraEndTick(startTick: Tick): Long = {
      import EraDuration.CalendarUnit._
      val UTC = ZoneId.of("UTC")

      conf.eraDuration match {
        case EraDuration.Ticks(ticks) =>
          startTick + ticks

        case EraDuration.Calendar(length, unit) =>
          val s = LocalDateTime.ofInstant(Instant.ofEpochMilli(toTimestamp(startTick)), UTC)
          val e = unit match {
            case SECONDS => s.plusSeconds(length)
            case MINUTES => s.plusMinutes(length)
            case HOURS   => s.plusHours(length)
            case DAYS    => s.plusDays(length)
            case WEEKS   => s.plusWeeks(length)
            case MONTHS  => s.plusMonths(length)
            case YEARS   => s.plusYears(length)
          }
          val t = e.atZone(UTC).toInstant.toEpochMilli
          toTicks(Timestamp(t))
      }
    }

    //def genesisEraLengthMultiplier
  }
}
