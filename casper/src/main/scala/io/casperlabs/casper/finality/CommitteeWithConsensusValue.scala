package io.casperlabs.casper.finality

import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.models.Weight

case class CommitteeWithConsensusValue(
    validator: Set[Validator],
    quorum: Weight,
    consensusValue: BlockHash
)
