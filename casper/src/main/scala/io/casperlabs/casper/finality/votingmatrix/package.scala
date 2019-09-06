package io.casperlabs.casper.finality

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.finality.votingmatrix.VotingMatrix.{Vote, VotingMatrix}
import io.casperlabs.catscontrib.MonadStateOps._

import scala.annotation.tailrec
import scala.collection.mutable.{IndexedSeq => MutableSeq}

package object votingmatrix {

  /**
    * Updates voting matrix when a new block added to dag
    * @param dag
    * @param blockMetadata the new block
    * @param currentVoteValue which branch the new block vote for
    * @return
    */
  def updateVoterPerspective[F[_]: Monad](
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata,
      currentVoteValue: BlockHash
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      validatorToIndex <- (matrix >> 'validatorToIdx).get
      voter            = blockMetadata.validatorPublicKey
      _ <- if (!validatorToIndex.contains(voter)) {
            // The creator of block isn't from the validatorsSet
            // e.g. It is bonded after creating the latestFinalizedBlock
            ().pure[F]
          } else {
            for {
              _ <- updateVotingMatrixOnNewBlock[F](dag, blockMetadata)
              _ <- updateFirstZeroLevelVote[F](voter, currentVoteValue, blockMetadata.rank)
            } yield ()
          }
    } yield ()

  /**
    * Checks whether provide branch should be finalized
    * @param rFTT the relative fault tolerance threshold
    * @return
    */
  def checkForCommittee[F[_]: Monad](
      rFTT: Double
  )(implicit matrix: VotingMatrix[F]): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap                 <- (matrix >> 'weightMap).get
      totalWeight               = weightMap.values.sum
      quorum                    = math.ceil(totalWeight * (rFTT + 0.5)).toLong
      committeeApproximationOpt <- findCommitteeApproximation[F](quorum)
      result <- committeeApproximationOpt match {
                 case Some(
                     CommitteeWithConsensusValue(committeeApproximation, _, consensusValue)
                     ) =>
                   for {
                     votingMatrix        <- (matrix >> 'votingMatrix).get
                     firstLevelZeroVotes <- (matrix >> 'firstLevelZeroVotes).get
                     validatorToIndex    <- (matrix >> 'validatorToIdx).get
                     mask = FinalityDetectorUtil
                       .fromMapToArray(validatorToIndex, committeeApproximation.contains)
                     weight = FinalityDetectorUtil
                       .fromMapToArray(validatorToIndex, weightMap.getOrElse(_, 0L))
                     committeeOpt = pruneLoop(
                       votingMatrix,
                       firstLevelZeroVotes,
                       consensusValue,
                       mask,
                       quorum,
                       weight
                     )
                     committee = committeeOpt.map {
                       case (mask, totalWeight) =>
                         val committee = validatorToIndex.filter { case (_, i) => mask(i) }.keySet
                         CommitteeWithConsensusValue(committee, totalWeight, consensusValue)
                     }
                   } yield committee
                 case None =>
                   none[CommitteeWithConsensusValue].pure[F]
               }
    } yield result

  private[votingmatrix] def updateVotingMatrixOnNewBlock[F[_]: Monad](
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata
  )(implicit matrix: VotingMatrix[F]): F[Unit] =
    for {
      validatorToIndex <- (matrix >> 'validatorToIdx).get
      panoramaM        <- FinalityDetectorUtil.panoramaM[F](dag, validatorToIndex, blockMetadata)
      // Replace row i in voting-matrix by panoramaM
      _ <- (matrix >> 'votingMatrix).modify(
            _.updated(validatorToIndex(blockMetadata.validatorPublicKey), panoramaM)
          )
    } yield ()

  private[votingmatrix] def updateFirstZeroLevelVote[F[_]: Monad](
      validator: Validator,
      newVote: BlockHash,
      dagLevel: Long
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
  private[votingmatrix] def findCommitteeApproximation[F[_]: Monad](
      quorum: Long
  )(implicit matrix: VotingMatrix[F]): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap           <- (matrix >> 'weightMap).get
      validators          <- (matrix >> 'validators).get
      firstLevelZeroVotes <- (matrix >> 'firstLevelZeroVotes).get
      // Get Map[VoteBranch, List[Validator]] directly from firstLevelZeroVotes
      consensusValueToValidators = firstLevelZeroVotes.zipWithIndex
        .collect { case (Some((blockHash, _)), idx) => (blockHash, validators(idx)) }
        .groupBy(_._1)
        .mapValues(_.map(_._2))
      // Get most support voteBranch and its support weight
      mostSupport = consensusValueToValidators
        .mapValues(_.map(weightMap.getOrElse(_, 0L)).sum)
        .maxBy(_._2)
      (voteValue, supportingWeight) = mostSupport
      // Get the voteBranch's supporters
      supporters = consensusValueToValidators(voteValue)
    } yield
      if (supportingWeight > quorum) {
        Some(CommitteeWithConsensusValue(supporters.toSet, supportingWeight, voteValue))
      } else {
        None
      }

  @tailrec
  private[votingmatrix] def pruneLoop(
      matrix: MutableSeq[MutableSeq[Long]],
      firstLevelZeroVotes: MutableSeq[Option[Vote]],
      candidateBlockHash: BlockHash,
      mask: MutableSeq[Boolean],
      q: Long,
      weight: MutableSeq[Long]
  ): Option[(MutableSeq[Boolean], Long)] = {
    val (newMask, prunedValidator, maxTotalWeight) = matrix.zipWithIndex
      .filter { case (_, rowIndex) => mask(rowIndex) }
      .foldLeft((mask, false, 0L)) {
        case ((newMask, prunedValidator, maxTotalWeight), (row, rowIndex)) =>
          val voteSum = row.zipWithIndex
            .filter { case (_, columnIndex) => mask(columnIndex) }
            .map {
              case (latestDagLevelSeen, columnIndex) =>
                firstLevelZeroVotes(columnIndex).fold(0L) {
                  case (consensusValue, dagLevelOf1stLevel0) =>
                    if (consensusValue == candidateBlockHash && dagLevelOf1stLevel0 <= latestDagLevelSeen)
                      weight(columnIndex)
                    else 0L
                }
            }
            .sum
          if (voteSum < q) {
            (newMask.updated(rowIndex, false), true, maxTotalWeight)
          } else {
            (newMask, prunedValidator, maxTotalWeight + weight(rowIndex))
          }
      }
    if (prunedValidator) {
      if (maxTotalWeight < q)
        // Terminate finality detection, finality is not reached yet.
        None
      else
        pruneLoop(matrix, firstLevelZeroVotes, candidateBlockHash, newMask, q, weight)
    } else {
      (mask, maxTotalWeight).some
    }
  }

}
