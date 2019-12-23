package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.Clock
import java.time.Instant
import io.casperlabs.casper.consensus.{BlockSummary, Era}
import io.casperlabs.crypto.Keys.PublicKeyBS
import io.casperlabs.models.Message

/** Class to encapsulate the message handling logic of messages in an era.
  *
  * It can create blocks/ballots and persist them, even new eras,
  * but it should not have externally visible side effects, i.e.
  * not communicate over the network.
  *
  * Persisting messages is fine: if we want to handle messages concurrently
  * we have to keep track of the validator sequence number and make sure
  * we cite our own previous messages. But it's best not to communicate
  * the changes to the outside world right here. We may want to replicate
  * them first in a master-slave environment, in which case we have to
  * make sure the slave has everything persisted as well, so that in the
  * even it has to take over, it won't equivocate.
  *
  * Therefore the handler methods will return a list of domain events,
  * and it's up to the supervisor protocol to send them out. Should
  * make testing easier as well.
  */
class EraRuntime[F[_]: Monad: Clock](
    conf: HighwayConf,
    val era: Era,
    maybeValidatorId: Option[PublicKeyBS],
    initRoundExponent: Int
) {
  import EraRuntime.Agenda, Agenda._

  val startTick = Ticks(era.startTick)
  val endTick   = Ticks(era.endTick)
  val start     = conf.toInstant(startTick)
  val end       = conf.toInstant(endTick)

  val bookingBoundaries =
    conf.criticalBoundaries(start, end, delayDuration = conf.bookingDuration)

  val keyBoundaries =
    conf.criticalBoundaries(start, end, delayDuration = conf.keyDuration)

  /** When we handle an incoming block or create a new one we may have to do additional work:
    * - if the block is a booking block, we have to execute the auction to pick the validators for the upcoming era
    * - when see a switch block, we have to look for a main ancestor which was a key block, to decide which era it belongs to.
    * These blocks can be identified by their round ID being after a boundary (a deadline),
    * while their main parent was still before the deadline.
    */
  private def isBoundary(boundaries: List[Instant])(
      mainParentBlockRoundId: Instant,
      blockRoundId: Instant
  ) = boundaries.exists(t => mainParentBlockRoundId.isBefore(t) && !blockRoundId.isBefore(t))

  val isBookingBoundary = isBoundary(bookingBoundaries)(_, _)
  val isKeyBoundary     = isBoundary(keyBoundaries)(_, _)

  /** Switch blocks are the first blocks which are created _after_ the era ends.
    * They are still created by the validators of this era, and they signal the
    * end of the era. Switch blocks are what the child era is going to build on,
    * however, the validators of _this_ era are the ones that can finalize it by
    * building ballots on top of it. They cannot build more blocks on them though.
    */
  val isSwitchBoundary = (mpbr: Instant, br: Instant) => mpbr.isBefore(end) && !br.isBefore(end)

  /** Whether the validator is bonded depends on the booking block. Only bonded validators
    * have to produce blocks and ballots in the era.
    * TODO: Decide if unbonded validators need to maintain round statistics about finalized lambda messages.
    */
  val isBonded =
    maybeValidatorId.fold(false)(id => era.bonds.exists(b => b.validatorPublicKey == id))

  private def currentTick =
    Clock[F].realTime(conf.tickUnit).map(Ticks(_))

  /** Check if the era is finished yet, including the post-era voting period. */
  private def isFinished(tick: Ticks): F[Boolean] =
    if (tick < endTick) false.pure[F]
    else {
      conf.postEraVotingDuration match {
        case HighwayConf.VotingDuration.FixedLength(duration) =>
          (end plus duration).isBefore(conf.toInstant(tick)).pure[F]

        case HighwayConf.VotingDuration.SummitLevel(_) =>
          ???
      }
    }

  /** Calculate the next tick where the round ID matches the exponent. */
  private def nextRoundId(tick: Ticks): Ticks = {
    val length = Ticks.roundLength(initRoundExponent)
    if (tick % length == 0) tick else Ticks(tick + length - tick % length)
  }

  /** Produce a starting agenda, depending on whether the validator is bonded or not. */
  def initAgenda: F[Agenda] =
    if (!isBonded) Agenda.empty[F]
    else
      currentTick flatMap { tick =>
        isFinished(tick) flatMap {
          case true =>
            Agenda.empty[F]
          case false =>
            val roundId = nextRoundId(tick)
            Agenda[F](roundId -> StartRound(roundId))
        }
      }

  /** Handle a block or ballot coming from another validator. For example:
    * - if it's a lambda message, create a lambda response
    * - if it's a switch block, create a new era, unless it exists already.
    * Returns a list of events that happened during the persisting of changes.
    * This method is always called as a reaction to an incoming message,
    * so it doesn't return a future agenda of its own.
    */
  def handleMessage(message: Message): HighwayLog[F, Unit] = ???

  /** Handle something that happens during a round:
    * - in rounds when we are leading, create a lambda message
    * - if it's a booking block, execute the auction
    * - if the main parent is a switch block, create a ballot instead
    * - midway through the round, create an omega message
    * - if we're beyond the voting period after the end of the era, stop.
    * Returns a list of events that took place, as well as its future agenda,
    * telling the caller when it has to be scheduled again.
    */
  def handleAgenda(action: Agenda.Action): HighwayLog[F, Agenda] = ???

}

object EraRuntime {

  def fromGenesis[F[_]: Monad: Clock](
      conf: HighwayConf,
      genesis: BlockSummary,
      maybeValidatorId: Option[PublicKeyBS],
      initRoundExponent: Int
  ): EraRuntime[F] =
    new EraRuntime[F](
      conf,
      Era(
        keyBlockHash = genesis.blockHash,
        bookingBlockHash = genesis.blockHash,
        startTick = conf.toTicks(conf.genesisEraStart),
        endTick = conf.toTicks(conf.genesisEraEnd),
        bonds = genesis.getHeader.getState.bonds
      ),
      maybeValidatorId,
      initRoundExponent
    )

  /** List of future actions to take. */
  type Agenda = Vector[Agenda.DelayedAction]

  object Agenda {
    sealed trait Action

    /** What action to take and when. */
    case class DelayedAction(tick: Ticks, action: Action)

    /** Handle one round:
      * - in rounds when we are leading, create a lambda message
      * - if it's a booking block, execute the auction
      * - if the main parent is a switch block, create a ballot instead
      * - if we're beyond the voting period after the end of the era, stop.
      */
    case class StartRound(roundId: Ticks) extends Action

    /** Create an Omega message, some time during the round */
    case class CreateOmegaMessage(roundId: Ticks) extends Action

    def apply[F[_]: Applicative](
        actions: (Ticks, Action)*
    ): F[Agenda] =
      actions.map(DelayedAction.tupled).toVector.pure[F]

    def empty[F[_]: Applicative] = apply[F]()
  }
}
