package io.casperlabs.casper.highway

import io.casperlabs.casper.consensus.{BlockSummary, Era}

class EraRuntime(conf: HighwayConf, val era: Era) {

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

  private def isBoundary(boundaries: List[Ticks])(
      mainParentBlockRoundId: Ticks,
      blockRoundId: Ticks
  ) = boundaries.exists(t => mainParentBlockRoundId < t && t <= blockRoundId)

  val isBookingBoundary = isBoundary(bookingBoundaries)(_, _)
  val isKeyBoundary     = isBoundary(keyBoundaries)(_, _)
  val isSwitchBoundary  = (mpbr: Ticks, br: Ticks) => mpbr < era.endTick && era.endTick <= br

}

object EraRuntime {
  def fromGenesis(conf: HighwayConf, genesis: BlockSummary): EraRuntime =
    new EraRuntime(
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
