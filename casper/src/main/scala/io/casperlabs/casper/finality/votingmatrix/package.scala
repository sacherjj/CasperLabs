package io.casperlabs.casper.finality

import cats.Monad
import cats.implicits._
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.finality.votingmatrix.VotingMatrix.{Vote, VotingMatrix}
import io.casperlabs.catscontrib.MonadStateOps._
import io.casperlabs.models.Message.{JRank, MainRank}
import io.casperlabs.models.{Message, Weight}
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.casper.validation.Validation
import io.casperlabs.shared.ByteStringPrettyPrinter.byteStringShow
import scala.annotation.tailrec
import scala.collection.mutable.{IndexedSeq => MutableSeq}

package object votingmatrix {

  import io.casperlabs.catscontrib.MonadThrowable

  import Weight.Implicits._
  import Weight.Zero

  /**
    * Updates voting matrix when a new block added to dag
    * @param dag
    * @param msg the new message
    * @param lfbChild which branch the new block vote for
    * @return
    */
  def updateVoterPerspective[F[_]: Monad](
      dag: DagRepresentation[F],
      msg: Message,
      messagePanorama: Map[Validator, Message],
      lfbChild: BlockHash,
      isHighway: Boolean
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      validatorToIndex <- (matrix >> 'validatorToIdx).get
      voter            = msg.validatorId
      _ <- if (!validatorToIndex.contains(voter)) {
            // The creator of block isn't from the validatorsSet
            // e.g. It is bonded after creating the latestFinalizedBlock
            ().pure[F]
          } else {
            for {
              _ <- updateVotingMatrixOnNewBlock[F](dag, msg, messagePanorama, isHighway)
              _ <- updateFirstZeroLevelVote[F](voter, lfbChild, msg.jRank)
            } yield ()
          }
    } yield ()

  /**
    * Checks whether provide branch should be finalized
    * @param rFTT the relative fault tolerance threshold
    * @return
    */
  def checkForCommittee[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      rFTT: Double,
      isHighway: Boolean
  )(
      implicit matrix: VotingMatrix[F]
  ): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap   <- matrix.get.map(_.weightMap)
      totalWeight = weightMap.values.sum
      quorum      = totalWeight * (rFTT + 0.5)
      committee <- findCommitteeApproximation[F](dag, quorum, isHighway)
                    .flatMap {
                      case Some(
                          CommitteeWithConsensusValue(committeeApproximation, _, consensusValue)
                          ) =>
                        for {
                          votingMatrix        <- matrix.get.map(_.votingMatrix)
                          firstLevelZeroVotes <- matrix.get.map(_.firstLevelZeroVotes)
                          validatorToIndex    <- matrix.get.map(_.validatorToIdx)
                          // A sequence of bits where 1 represents an i-th validator present
                          // in the committee approximation.
                          validatorsMask = FinalityDetectorUtil
                            .fromMapToArray(validatorToIndex, committeeApproximation.contains)
                          // A sequence of validators' weights.
                          weight = FinalityDetectorUtil
                            .fromMapToArray(validatorToIndex, weightMap.getOrElse(_, Zero))
                          committee = pruneLoop(
                            votingMatrix,
                            firstLevelZeroVotes,
                            consensusValue,
                            validatorsMask,
                            quorum,
                            weight
                          ) map {
                            case (mask, totalWeight) =>
                              val committee = validatorToIndex.filter { case (_, i) => mask(i) }.keySet
                              CommitteeWithConsensusValue(committee, totalWeight, consensusValue)
                          }
                        } yield committee
                      case None =>
                        none[CommitteeWithConsensusValue].pure[F]
                    }
    } yield committee

  private[votingmatrix] def updateVotingMatrixOnNewBlock[F[_]: Monad](
      dag: DagRepresentation[F],
      msg: Message,
      messagePanorama: Map[Validator, Message],
      isHighway: Boolean
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      validatorToIndex <- (matrix >> 'validatorToIdx).get
      panoramaM <- FinalityDetectorUtil
                    .panoramaM[F](dag, validatorToIndex, msg, messagePanorama, isHighway)
      // Replace row i in voting-matrix by panoramaM
      _ <- (matrix >> 'votingMatrix).modify(
            _.updated(validatorToIndex(msg.validatorId), panoramaM)
          )
    } yield ()

  private[votingmatrix] def updateFirstZeroLevelVote[F[_]: Monad](
      validator: Validator,
      newVote: BlockHash,
      dagLevel: JRank
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      firstLevelZeroMsgs <- (matrix >> 'firstLevelZeroVotes).get
      validatorToIndex   <- (matrix >> 'validatorToIdx).get
      voterIdx           = validatorToIndex(validator)
      firstLevelZeroVote = firstLevelZeroMsgs(voterIdx)
      _ <- firstLevelZeroVote match {
            case None =>
              (matrix >> 'firstLevelZeroVotes).modify(
                _.updated(voterIdx, Some((newVote, dagLevel)))
              )
            case Some((prevVote, _)) if prevVote != newVote =>
              (matrix >> 'firstLevelZeroVotes).modify(
                _.updated(voterIdx, Some((newVote, dagLevel)))
              )
            case Some((prevVote, _)) if prevVote == newVote =>
              ().pure[F]
          }
    } yield ()

  /**
    * Phase 1 - finding most supported consensus value,
    * if its supporting vote larger than quorum, return it and its supporter
    * else return None
    * @param quorum
    * @return
    */
  private[votingmatrix] def findCommitteeApproximation[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      quorum: Weight,
      isHighway: Boolean
  )(implicit matrix: VotingMatrix[F]): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap           <- matrix.get.map(_.weightMap)
      validators          <- matrix.get.map(_.validators)
      firstLevelZeroVotes <- matrix.get.map(_.firstLevelZeroVotes)
      // Get Map[VoteBranch, List[Validator]] directly from firstLevelZeroVotes
      committee <- if (firstLevelZeroVotes.isEmpty) {
                    // No one voted on anything in the current b-game
                    none[CommitteeWithConsensusValue].pure[F]
                  } else
                    firstLevelZeroVotes.zipWithIndex
                      .collect {
                        case (Some((blockHash, _)), idx) =>
                          (blockHash, validators(idx))
                      }
                      .toList
                      .filterA {
                        case (voteHash, validator) =>
                          // Get rid of validators' votes.
                          // Need to cater for both NCB and Highway modes.
                          val highwayCheck = for {
                            voteMsg <- dag.lookupUnsafe(voteHash)
                            eraEquivocators <- dag.getEquivocatorsInEra(
                                                voteMsg.eraId
                                              )
                          } yield !eraEquivocators.contains(validator)

                          val ncbCheck =
                            dag.getEquivocators.map(!_.contains(validator))

                          if (isHighway) highwayCheck else ncbCheck
                      }
                      .map(_.groupBy(_._1).mapValues(_.map(_._2)))
                      .map { consensusValueToHonestValidators =>
                        if (consensusValueToHonestValidators.isEmpty) {
                          // After filtering out equivocators we don't have any honest votes.
                          none[CommitteeWithConsensusValue]
                        } else {
                          // Get most support voteBranch and its support weight
                          val mostSupport = consensusValueToHonestValidators
                            .mapValues(_.map(weightMap.getOrElse(_, Zero)).sum)
                            .maxBy(_._2)

                          val (voteValue, supportingWeight) = mostSupport
                          // Get the voteBranch's supporters
                          val supporters = consensusValueToHonestValidators(voteValue)
                          if (supportingWeight > quorum) {
                            Some(
                              CommitteeWithConsensusValue(
                                supporters.toSet,
                                supportingWeight,
                                voteValue
                              )
                            )
                          } else None
                        }
                      }
    } yield committee

  @tailrec
  private[votingmatrix] def pruneLoop(
      // Matrix of the latest j-DAG level of messages that a validator in row i saw from a validator in column j.
      validatorsViews: MutableSeq[MutableSeq[Level]],
      // Which candidate (if any) does each validator vote for and since which level.
      firstLevelZeroVotes: MutableSeq[Option[Vote]],
      candidateBlockHash: BlockHash,
      // Which validators were (and are still) part of the committee.
      mask: MutableSeq[Boolean],
      q: Weight,
      weight: MutableSeq[Weight]
  ): Option[(MutableSeq[Boolean], Weight)] = {
    // Go through each validator in the matrix rows and see if
    // enough other validators saw them voting for the candidate.
    val (newMask, prunedValidator, maxTotalWeight) = validatorsViews.zipWithIndex
      .filter { case (_, voterIndex) => mask(voterIndex) }
      .foldLeft((mask, false, Zero)) {
        case ((newMask, prunedValidator, maxTotalWeight), (_, voterIndex)) =>
          // Does this validator have enough witnesses behind it so that it can add its support to the candidate?
          val voteSum =
            // See if this validator is indeed voting for the candidate.
            firstLevelZeroVotes(voterIndex) match {
              case Some((voterBlockHash, voterSinceLevel))
                  if voterBlockHash == candidateBlockHash =>
                // Add up the weight of other validators who have seen this validator vote for the candidate.
                // For validator i this should be based on the i-th column in each row of the matrix,
                // because that's where we see which is the latest level other validators have seen from it.
                validatorsViews.zipWithIndex
                  .filter { case (_, witnessIndex) => mask(witnessIndex) }
                  .map {
                    case (row, witnessIndex) =>
                      // What's the latest level the witness has seen from the voter?
                      val witnessedVoterLevel = row(voterIndex)
                      // See if the witness is voting for the same candidate.
                      firstLevelZeroVotes(witnessIndex) match {
                        case Some((witnessBlockHash, _))
                            if witnessBlockHash == candidateBlockHash && witnessedVoterLevel >= voterSinceLevel =>
                          // The validator at `witnessIndex` puts their weight behind the one at `voterIndex`
                          weight(witnessIndex)

                        // Voting for a different candidate or hasn't seen the voter's vote yet.
                        case _ => Zero
                      }
                  }
                  .sum

              // Not voting for the candidate.
              case _ => Zero
            }

          // If not enough other validators see this one, eliminate this validator from the committee.
          // Otherwise add their weight to the vote behind the candidate itself.
          if (voteSum < q) {
            (newMask.updated(voterIndex, false), true, maxTotalWeight)
          } else {
            (newMask, prunedValidator, maxTotalWeight + weight(voterIndex))
          }
      }
    if (prunedValidator) {
      if (maxTotalWeight < q)
        // Terminate finality detection, finality is not reached yet.
        None
      else
        pruneLoop(validatorsViews, firstLevelZeroVotes, candidateBlockHash, newMask, q, weight)
    } else {
      (mask, maxTotalWeight).some
    }
  }

}
