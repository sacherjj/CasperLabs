package io.casperlabs.casper.genesis.contracts
import io.casperlabs.crypto.codec.Base16

//TODO: include other fields relevent to PoS (e.g. rewards channel)
final case class ProofOfStakeValidator(id: Array[Byte], stake: Long)

final case class ProofOfStakeParams(
    minimumBond: Long,
    maximumBond: Long,
    validators: Seq[ProofOfStakeValidator]
) {
  require(minimumBond <= maximumBond)
  require(validators.nonEmpty)
}
