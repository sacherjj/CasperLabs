package io.casperlabs.casper.finality

import io.casperlabs.casper.Estimator.{BlockHash, Validator}

case class CommitteeWithConsensusValue(
    validator: Set[Validator],
    quorum: Long,
    consensusValue: BlockHash
)
