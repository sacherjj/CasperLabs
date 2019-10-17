package io.casperlabs.models

import io.casperlabs.casper.consensus.state

object Weight {
  object Implicits {
    implicit class RichBigInt(i: BigInt) {
      def *(d: Double): BigInt =
        BigDecimal(i.toDouble * d).setScale(0, BigDecimal.RoundingMode.CEILING).toBigInt
    }
  }

  val Zero = BigInt(0)

  def apply(stake: Option[state.BigInt]) =
    stake.fold(Zero)(x => BigInt(x.value))

  def apply(i: Int) =
    BigInt(i)
}
