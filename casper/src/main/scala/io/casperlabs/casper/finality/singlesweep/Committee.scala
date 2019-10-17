package io.casperlabs.casper.finality.singlesweep

import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.models.Weight

case class Committee(validators: Set[Validator], quorum: Weight)
