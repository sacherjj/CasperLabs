package io.casperlabs.casper.finality.votingmatrix

import cats.Monad
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.mtl.{DefaultMonadState, MonadState}
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.finality.{CommitteeWithConsensusValue, FinalityDetectorUtil}
import io.casperlabs.casper.finality.votingmatrix.VotingMatrixImpl.{Vote, VotingMatrix}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.MonadStateOps._

import scala.annotation.tailrec
import scala.collection.mutable.{IndexedSeq => MutableSeq}

object VotingMatrix {

  /**
    * Update voting matrix when a new block added to dag
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

  def updateVotingMatrixOnNewBlock[F[_]: Monad](
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

  private def updateFirstZeroLevelVote[F[_]: Monad](
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
    * Check whether provide branch should be finalized
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
                     mask = VotingMatrixImpl
                       .fromMapToArray(validatorToIndex, committeeApproximation.contains)
                     weight = VotingMatrixImpl
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

  /**
    * Phase 1 - finding most supported consensus value,
    * if its supporting vote larger than quorum, return it and its supporter
    * else return None
    * @param quorum
    * @return
    */
  private def findCommitteeApproximation[F[_]: Monad](
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
  private def pruneLoop(
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

object VotingMatrixImpl {
  // (Consensus value, DagLevel of the block)
  type Vote               = (BlockHash, Long)
  type VotingMatrix[F[_]] = MonadState[F, VotingMatrixState]

  def of[F[_]: Sync](
      votingMatrixState: VotingMatrixState
  ): F[VotingMatrix[F]] =
    Ref[F]
      .of(votingMatrixState)
      .map(
        state =>
          new DefaultMonadState[F, VotingMatrixState] {
            val monad: cats.Monad[F]               = implicitly[Monad[F]]
            def get: F[VotingMatrixState]          = state.get
            def set(s: VotingMatrixState): F[Unit] = state.set(s)
          }
      )

  case class VotingMatrixState(
      votingMatrix: MutableSeq[MutableSeq[Long]],
      firstLevelZeroVotes: MutableSeq[Option[Vote]],
      validatorToIdx: Map[Validator, Int],
      weightMap: Map[Validator, Long],
      validators: MutableSeq[Validator]
  )

  def empty[F[_]: Concurrent]: F[VotingMatrix[F]] =
    of[F](
      VotingMatrixState(MutableSeq.empty, MutableSeq.empty, Map.empty, Map.empty, MutableSeq.empty)
    )

  // Returns an MutableSeq, whose size equals the size of validatorsToIndex and
  // For v in validatorsToIndex.key
  //   Arr[validatorsToIndex[v]] = mapFunction[v]
  def fromMapToArray[A](
      validatorsToIndex: Map[Validator, Int],
      mapFunction: Validator => A
  ): MutableSeq[A] =
    validatorsToIndex
      .map {
        case (v, i) =>
          (i, mapFunction(v))
      }
      .toArray[(Int, A)]
      .sortBy(_._1)
      .map(_._2)

  /**
    * create a new voting matrix basing new finalized block
    */
  def create[F[_]: Concurrent](
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[VotingMatrixState] =
    for {
      // Start a new round, get weightMap and validatorSet from the post-global-state of new finalized block's
      weights    <- dag.lookup(newFinalizedBlock).map(_.get.weightMap)
      validators = weights.keySet.toArray
      n          = validators.size
      // Assigns numeric identifiers 0, ..., N-1 to all validators
      validatorsToIndex      = validators.zipWithIndex.toMap
      latestMessages         <- dag.latestMessages
      latestMessagesOfVoters = latestMessages.filterKeys(validatorsToIndex.contains)
      latestVoteValueOfVotesAsList <- latestMessagesOfVoters.toList
                                       .traverse {
                                         case (v, b) =>
                                           ProtoUtil
                                             .votedBranch[F](
                                               dag,
                                               newFinalizedBlock,
                                               b.blockHash
                                             )
                                             .map {
                                               _.map(
                                                 branch => (v, branch)
                                               )
                                             }
                                       }
                                       .map(_.flatten)
      // Traverse down the swim lane of V(i) to find the earliest block voting
      // for the same Fm's child as V(i) latest does.
      // This way we can initialize first-zero-level-messages(i).
      firstLevelZeroVotes <- latestVoteValueOfVotesAsList
                              .traverse {
                                case (v, voteValue) =>
                                  FinalityDetectorUtil
                                    .levelZeroMsgsOfValidator(dag, v, voteValue)
                                    .map(
                                      _.lastOption
                                        .map(b => (v, (voteValue, b.rank)))
                                    )
                              }
                              .map(_.flatten.toMap)
      firstLevelZeroVotesArray = fromMapToArray(
        validatorsToIndex,
        firstLevelZeroVotes.get
      )
      latestMessagesToUpdated = latestMessagesOfVoters.filterKeys(
        firstLevelZeroVotes.contains
      )
      state = VotingMatrixState(
        MutableSeq.fill(n, n)(0),
        firstLevelZeroVotesArray,
        validatorsToIndex,
        weights,
        validators
      )
      implicit0(votingMatrix: VotingMatrix[F]) <- of[F](state)
      // Apply the incremental update step to update voting matrix by taking M := V(i)latest
      _ <- latestMessagesToUpdated.values.toList.traverse { b =>
            VotingMatrix.updateVotingMatrixOnNewBlock[F](dag, b)
          }
      updatedState <- votingMatrix.get
    } yield updatedState
}
