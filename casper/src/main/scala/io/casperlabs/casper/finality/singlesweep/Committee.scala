package io.casperlabs.casper.finality.singlesweep

import io.casperlabs.casper.Estimator.Validator

case class Committee(validators: Set[Validator], quorum: Long)
