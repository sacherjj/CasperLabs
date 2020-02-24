package io.casperlabs.casper.highway

import org.scalatest._

class TicksSpec extends FlatSpec with Matchers {

  "roundLength" should "calculate the length as 2^round_exponent" in {
    Ticks.roundLength(0) shouldBe 1L
    Ticks.roundLength(1) shouldBe 2L
    Ticks.roundLength(2) shouldBe 4L
  }

  "nextRound" should "give the next available round" in {
    val exp   = 4
    val epoch = Ticks(0L)
    val next  = Ticks.nextRound(epoch, exp)(_)
    next(Ticks(15L)) shouldBe 16L
    next(Ticks(16L)) shouldBe 32L
    next(Ticks(17L)) shouldBe 32L
    next(Ticks(32L)) shouldBe 48L
  }

  it should "take the epoch into account" in {
    val exp   = 3
    val epoch = Ticks(5L)
    Ticks.nextRound(epoch, exp)(Ticks(7L)) shouldBe 13L
  }
}
