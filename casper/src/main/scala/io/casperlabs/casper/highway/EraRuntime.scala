package io.casperlabs.casper.highway

import io.casperlabs.casper.consensus.{BlockSummary, Era}
import io.casperlabs.crypto.Keys.PublicKeyBS

class EraRuntime[F[_]](conf: HighwayConf, val era: Era) {

  val bookingBoundaries =
    conf.criticalBoundaries(
      Ticks(era.startTick),
      Ticks(era.endTick),
      delayTicks = conf.bookingTicks
    )

  val keyBoundaries =
    conf.criticalBoundaries(
      Ticks(era.startTick),
      Ticks(era.endTick),
      delayTicks = conf.keyTicks
    )

  /** When we handle an incoming block or create a new one we may have to do additional work:
    * - if the block is a booking block, we have to execute the auction to pick the validators for the upcoming era
    * - when see a switch block, we have to look for a main ancestor which was a key block, to decide which era it belongs to.
    * These blocks can be identified by their round ID being after a boundary (a deadline),
    * while their main parent was still before the deadline.
    */
  private def isBoundary(boundaries: List[Ticks])(
      mainParentBlockRoundId: Ticks,
      blockRoundId: Ticks
  ) = boundaries.exists(t => mainParentBlockRoundId < t && t <= blockRoundId)

  val isBookingBoundary = isBoundary(bookingBoundaries)(_, _)
  val isKeyBoundary     = isBoundary(keyBoundaries)(_, _)

  /** Switch blocks are the first blocks which are created _after_ the era ends.
    * They are still created by the validators of this era, and they signal the
    * end of the era. Otherwise there might be just one more millisecond round
    * in the era that you have to wait for. Switch blocks are what the child
    * era is going to build on, however, the validators of _this_ era are the
    * ones that can finalize it by building ballots on top of it. The cannot
    * build more blocks on them though.
    */
  val isSwitchBoundary = (mpbr: Ticks, br: Ticks) => mpbr < era.endTick && era.endTick <= br

}

object EraRuntime {
  def fromGenesis[F[_]](conf: HighwayConf, genesis: BlockSummary): EraRuntime[F] =
    new EraRuntime[F](
      conf,
      Era(
        keyBlockHash = genesis.blockHash,
        bookingBlockHash = genesis.blockHash,
        startTick = conf.genesisEraStartTick,
        endTick = conf.genesisEraEndTick,
        bonds = genesis.getHeader.getState.bonds
      )
    )
}
