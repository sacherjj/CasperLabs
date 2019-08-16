package io.casperlabs.casper

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.Monad
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.FinalityDetector.{CommitteeWithConsensusValue}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.VotingMatrixImpl.Vote
import simulacrum.typeclass

import scala.annotation.tailrec

@typeclass trait VotingMatrix[F[_]] {

  /**
    * Update voting matrix when a new block added to dag
    * @param dag
    * @param blockMetadata the new block
    * @param currentVoteValue which branch the new block vote for
    * @return
    */
  def updateVoterPerspective(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata,
      currentVoteValue: BlockHash
  ): F[Unit]

  /**
    * Check whether provide branch should be finalized
    * @param candidateBlockHash the provide branch to be checked whether finalized
    * @param committeeApproximation the pruned validator set that vote for the candidateBlockHash
    * @return
    */
  def checkForCommittee(
      candidateBlockHash: BlockHash,
      committeeApproximation: Set[Validator]
  ): F[Option[CommitteeWithConsensusValue]]

  /**
    * Start a new game when finalized a block, rebuild voting matrix
    * @param dag
    * @param newFinalizedBlock
    * @return
    */
  def rebuildFromLatestFinalizedBlock(
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[Unit]

  /**
    * Phase 1 - finding most supported consensus value
    * @param dag block dag
    * @return
    */
  def findCommitteeApproximation(dag: DagRepresentation[F]): F[Option[CommitteeWithConsensusValue]]
}

class VotingMatrixImpl[F[_]] private (
    implicit monad: Monad[F],
    matrixRef: Ref[F, List[List[Long]]],
    firstLevelZeroVotesRef: Ref[F, List[Option[Vote]]],
    validatorToIndexRef: Ref[F, Map[Validator, Int]],
    weightMapRef: Ref[F, Map[Validator, Long]],
    validatorsRef: Ref[F, List[Validator]]
) extends VotingMatrix[F] {
  override def updateVoterPerspective(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata,
      currentVoteValue: BlockHash
  ): F[Unit] =
    for {
      validatorToIndex <- validatorToIndexRef.get
      voter            = blockMetadata.validatorPublicKey
      _ <- if (!validatorToIndex.contains(voter)) {
            // The creator of block isn't from the validatorsSet
            // e.g. It is bonded after creating the latestFinalizedBlock
            ().pure[F]
          } else {
            for {
              _ <- updateVotingMatrixOnNewBlock(dag, blockMetadata)
              _ <- updateFirstZeroLevelVote(voter, currentVoteValue, blockMetadata.rank)
            } yield ()
          }
    } yield ()

  private def updateVotingMatrixOnNewBlock(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata
  ): F[Unit] =
    for {
      validatorToIndex <- validatorToIndexRef.get
      panoramaM        <- panoramaM(dag, validatorToIndex, blockMetadata)
      // Replace row i in voting-matrix by panoramaM
      _ <- matrixRef.update(
            matrix =>
              matrix.updated(
                validatorToIndex(blockMetadata.validatorPublicKey),
                panoramaM
              )
          )
    } yield ()

  private def updateFirstZeroLevelVote(
      validator: Validator,
      newVote: BlockHash,
      dagLevel: Long
  ): F[Unit] =
    for {
      firstLevelZeroMsgs <- firstLevelZeroVotesRef.get
      validatorToIndex   <- validatorToIndexRef.get
      voterIdx           = validatorToIndex(validator)
      firstLevelZeroVote = firstLevelZeroMsgs(voterIdx)
      _ <- firstLevelZeroVote match {
            case None =>
              firstLevelZeroVotesRef.update(_.updated(voterIdx, Some((newVote, dagLevel))))
            case Some((prevVote, _)) if prevVote != newVote =>
              firstLevelZeroVotesRef.update(_.updated(voterIdx, Some((newVote, dagLevel))))
            case Some((prevVote, _)) if prevVote == newVote =>
              ().pure[F]
          }
    } yield ()

  override def checkForCommittee(
      candidateBlockHash: BlockHash,
      committeeApproximation: Set[Validator]
  ): F[Option[CommitteeWithConsensusValue]] =
    for {
      matrix              <- matrixRef.get
      firstLevelZeroVotes <- firstLevelZeroVotesRef.get
      validatorToIndex    <- validatorToIndexRef.get
      weightMap           <- weightMapRef.get
      mask                = fromMapToArray(validatorToIndex, committeeApproximation.contains)
      weight              = fromMapToArray(validatorToIndex, weightMap.getOrElse(_, 0L))
      committeeOpt = pruneLoop(
        matrix,
        firstLevelZeroVotes,
        candidateBlockHash,
        mask,
        weight.sum / 2 + 1,
        weight
      )
      committee = committeeOpt.map {
        case (mask, totalWeight) =>
          val committee = validatorToIndex.filter { case (_, i) => mask(i) }.keySet
          CommitteeWithConsensusValue(committee, totalWeight, candidateBlockHash)
      }
    } yield committee

  override def findCommitteeApproximation(
      dag: DagRepresentation[F]
  ): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap           <- weightMapRef.get
      validators          <- validatorsRef.get
      firstLevelZeroVotes <- firstLevelZeroVotesRef.get
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
      totalWeight = weightMap.values.sum
    } yield
      if (supportingWeight * 2 > totalWeight) {
        Some(
          CommitteeWithConsensusValue(supporters.toSet, supportingWeight, voteValue)
        )
      } else {
        None
      }

  @tailrec
  private def pruneLoop(
      matrix: List[List[Long]],
      firstLevelZeroVotes: List[Option[Vote]],
      candidateBlockHash: BlockHash,
      mask: List[Boolean],
      q: Long,
      weight: List[Long]
  ): Option[(List[Boolean], Long)] = {
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
          if (voteSum >= q) {
            (newMask, prunedValidator, maxTotalWeight + weight(rowIndex))
          } else {
            (newMask.updated(rowIndex, false), true, maxTotalWeight)
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

  override def rebuildFromLatestFinalizedBlock(
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[Unit] =
    for {
      // todo(abner)  use a lock to atomically update these state
      // Start a new round, get weightMap and validatorSet from the post-global-state of new finalized block's
      weights    <- dag.lookup(newFinalizedBlock).map(_.get.weightMap)
      _          <- weightMapRef.set(weights)
      validators = weights.keySet.toList
      _          <- validatorsRef.set(validators)
      n          = validators.size
      _          <- matrixRef.set(List.fill(n, n)(0))
      _          <- firstLevelZeroVotesRef.set(List.fill(n)(None))
      // Assigns numeric identifiers 0, ..., N-1 to all validators
      validatorsToIndex      = validators.zipWithIndex.toMap
      _                      <- validatorToIndexRef.set(validatorsToIndex)
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
                                        .map(b => (v, (b.blockHash, b.rank)))
                                    )
                              }
                              .map(_.flatten.toMap)
      firstLevelZeroVotesArray = fromMapToArray(
        validatorsToIndex,
        firstLevelZeroVotes.get
      )
      _ <- firstLevelZeroVotesRef.set(firstLevelZeroVotesArray)
      latestMessagesToUpdated = latestMessagesOfVoters.filterKeys(
        firstLevelZeroVotes.contains
      )
      // Apply the incremental update step to update voting matrix by taking M := V(i)latest
      _ <- latestMessagesToUpdated.values.toList.traverse { b =>
            updateVotingMatrixOnNewBlock(dag, b)
          }
    } yield ()

  // Return an Array, whose size equals the size of validatorsToIndex and
  // For v in validatorsToIndex.key
  //   Arr[validatorsToIndex[v]] = mapFunction[v]
  private def fromMapToArray[A](
      validatorsToIndex: Map[Validator, Int],
      mapFunction: Validator => A
  ): List[A] =
    validatorsToIndex
      .map {
        case (v, i) =>
          (i, mapFunction(v))
      }
      .toList
      .sortBy(_._1)
      .map(_._2)

  private def panoramaM(
      dag: DagRepresentation[F],
      validatorsToIndex: Map[Validator, Int],
      blockMetadata: BlockMetadata
  ): F[List[Long]] =
    FinalityDetectorUtil
      .panoramaDagLevelsOfBlock(
        dag,
        blockMetadata,
        validatorsToIndex.keySet
      )
      .map(
        latestBlockDagLevelsAsMap =>
          // In cases where latest message of V(i) is not well defined, put 0L in the corresponding cell
          fromMapToArray(
            validatorsToIndex,
            latestBlockDagLevelsAsMap.getOrElse(_, 0L)
          )
      )
}

object VotingMatrixImpl {
  // (Consensus value, DagLevel of the block)
  type Vote = (BlockHash, Long)

  def empty[F[_]: Sync]: F[VotingMatrix[F]] =
    for {
      matrixRef              <- Ref.of[F, List[List[Long]]](List.empty)
      firstLevelZeroVotesRef <- Ref.of[F, List[Option[Vote]]](List.empty)
      validatorToIndexRef    <- Ref.of[F, Map[Validator, Int]](Map.empty)
      weightMapRef           <- Ref.of[F, Map[Validator, Long]](Map.empty)
      validatorsRef          <- Ref.of[F, List[Validator]](List.empty)
      votingMatrix = new VotingMatrixImpl[F]()(
        Sync[F],
        matrixRef,
        firstLevelZeroVotesRef,
        validatorToIndexRef,
        weightMapRef,
        validatorsRef
      )
    } yield votingMatrix
}
