package io.casperlabs.catscontrib

trait Opses
    extends ToMonadOps
    with ToBooleanOps
    with ToBooleanF
    with ToApplicativeError_Ops
    with ToTaskableOps

object Catscontrib extends Opses
