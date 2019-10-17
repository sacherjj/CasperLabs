package io.casperlabs.casper.finality.votingmatrix

import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.casper.finality.Level
import io.casperlabs.casper.finality.votingmatrix.VotingMatrix.Vote
import io.casperlabs.models.Weight
import scala.collection.mutable.{IndexedSeq => MutableSeq}

private[votingmatrix] case class VotingMatrixState(
    votingMatrix: MutableSeq[MutableSeq[Level]],
    firstLevelZeroVotes: MutableSeq[Option[Vote]],
    validatorToIdx: Map[Validator, Int],
    weightMap: Map[Validator, Weight],
    validators: MutableSeq[Validator]
)
