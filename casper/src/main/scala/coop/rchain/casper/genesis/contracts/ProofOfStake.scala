package coop.rchain.casper.genesis.contracts

//TODO: include other fields relevent to PoS (e.g. rewards channel)
case class ProofOfStakeValidator(id: Array[Byte], stake: Long)

case class ProofOfStakeParams(
    minimumBond: Long,
    maximumBond: Long,
    validators: Seq[ProofOfStakeValidator]
) {
  require(minimumBond <= maximumBond)
  require(validators.nonEmpty)
}

object ProofOfStake {
  def initialBondsCode(validators: Seq[ProofOfStakeValidator]): String = ???
}
