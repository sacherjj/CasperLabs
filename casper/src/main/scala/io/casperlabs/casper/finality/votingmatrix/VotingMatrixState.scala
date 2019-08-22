package io.casperlabs.casper.finality.votingmatrix

import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.casper.finality.votingmatrix.VotingMatrix.Vote
import scala.collection.mutable.{IndexedSeq => MutableSeq}

private[votingmatrix] case class VotingMatrixState(
    votingMatrix: MutableSeq[MutableSeq[Long]],
    firstLevelZeroVotes: MutableSeq[Option[Vote]],
    validatorToIdx: Map[Validator, Int],
    weightMap: Map[Validator, Long],
    validators: MutableSeq[Validator]
)
