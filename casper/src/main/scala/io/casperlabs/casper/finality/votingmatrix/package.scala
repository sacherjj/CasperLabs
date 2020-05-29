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
      // Which validators were (and are still) part of the committee that vote for this candidate.
      mask: MutableSeq[Boolean],
      q: Weight,
      weight: MutableSeq[Weight]
  ): Option[(MutableSeq[Boolean], Weight)] = {
    // A level-1 summit with 2/3 quorum is just 2/3 of validators having seen each other vote for the candidate.
    // Go through each validator in the matrix rows and see if they see a quorum of other validators vote for the candidate.
    val (newMask, prunedValidator, maxTotalWeight) = validatorsViews.zipWithIndex
      .filter { case (_, voterIndex) => mask(voterIndex) }
      .foldLeft((mask, false, Zero)) {
        case ((newMask, prunedValidator, maxTotalWeight), (row, voterIndex)) =>
          // Does this validator see enough of the others so that it can add its support to the candidate?
          // i.e. is this validator part of the committee?
          val voteSum =
            // Add up the weight of other validators who this validator sees voting for the candidate in its panorama.
            row.zipWithIndex
              .filter { case (_, witnessedIndex) => mask(witnessedIndex) }
              .map {
                case (witnessedHighestLevel, witnessedIndex) =>
                  // See if the witnessed level includes the vote for the candidate.
                  firstLevelZeroVotes(witnessedIndex) match {
                    case Some((_, witnessedVoteLevel))
                        if witnessedVoteLevel <= witnessedHighestLevel =>
                      // The validator at `witnessedIndex` puts their weight behind the one at `voterIndex`
                      weight(witnessedIndex)

                    // The voter hasn't witnessed the vote.
                    case _ => Zero
                  }
              }
              .sum

          // If this validator hasn't seen a qourum of others' votes, eliminate it from the committee,
          // otherwise add its weight to the candidate support.
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
