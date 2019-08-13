package io.casperlabs.casper

import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.Monad
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.FinalityDetector.Committee
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.VotingMatrixImpl.Vote
import simulacrum.typeclass

import scala.annotation.tailrec

@typeclass trait VotingMatrix[F[_]] {
  def updateVoterPerspective(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata,
      currentVoteValue: BlockHash
  ): F[Unit]

  def checkForCommittee(
      candidateBlockHash: BlockHash,
      committeeApproximation: Set[Validator],
      q: Long,
      weight: Map[Validator, Long]
  ): F[Option[Committee]]

  def rebuildFromLatestFinalizedBlock(
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[Unit]
}

class VotingMatrixImpl[F[_]] private (
    implicit monad: Monad[F],
    matrixRef: Ref[F, List[List[Long]]],
    firstLevelZeroVotesRef: Ref[F, List[Option[Vote]]],
    validatorToIndexRef: Ref[F, Map[Validator, Int]],
    weightMapRef: Ref[F, Map[Validator, Long]]
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
              _                   <- updateVotingMatrixOnNewBlock(dag, blockMetadata)
              currentDagLevel     = blockMetadata.rank
              firstLevelZeroVotes <- firstLevelZeroVotesRef.get

              indexOfVoter = validatorToIndex(voter)
              rowOpt       = firstLevelZeroVotes(indexOfVoter)
              _ <- rowOpt match {
                    case Some((finalVoteValue, _)) =>
                      if (finalVoteValue != currentVoteValue)
                        firstLevelZeroVotesRef.update(
                          l =>
                            l.updated(
                              indexOfVoter,
                              (currentVoteValue, currentDagLevel).some
                            )
                        )
                      else {
                        ().pure[F]
                      }
                    case None =>
                      firstLevelZeroVotesRef.update(
                        l =>
                          l.updated(
                            indexOfVoter,
                            (currentVoteValue, currentDagLevel).some
                          )
                      )
                  }
            } yield ()
          }
    } yield ()

  private def updateVotingMatrixOnNewBlock(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata
  ): F[Unit] =
    for {
      validatorToIndex <- validatorToIndexRef.get
      validators       = validatorToIndex.keySet
      latestBlockDagLevelsAsMap <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                                    dag,
                                    blockMetadata,
                                    validators
                                  )
      // In cases where latest message of V(i) is not well defined, put 0L in the corresponding cell
      panoramaM = fromMapToArray(
        validatorToIndex,
        latestBlockDagLevelsAsMap.getOrElse(_, 0L)
      )
      // Replace row i in voting-matrix by panoramaM
      _ <- matrixRef.update(
            matrix =>
              matrix.updated(
                validatorToIndex(blockMetadata.validatorPublicKey),
                panoramaM
              )
          )
    } yield ()

  override def checkForCommittee(
      candidateBlockHash: BlockHash,
      committeeApproximation: Set[Validator],
      q: Long,
      weight: Map[Validator, Long]
  ): F[Option[Committee]] =
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
        q,
        weight
      )
      committee = committeeOpt.map {
        case (mask, totalWeight) =>
          val committee = validatorToIndex.filter { case (_, i) => mask(i) }.keySet
          Committee(committee, totalWeight)
      }
    } yield committee

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
          val voteSum = firstLevelZeroVotes.zipWithIndex.map {
            case (Some((consensusValue, dagLevel)), columnIndex) =>
              if (consensusValue == candidateBlockHash && dagLevel <= row(columnIndex))
                weight(columnIndex)
              else 0L
            case _ => 0L
          }.sum
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
      // Start a new round, get weightMap and validatorSet from the new finalized block's main parent
      weights    <- ProtoUtil.mainParentWeightMap(dag, newFinalizedBlock)
      _          <- weightMapRef.set(weights)
      validators = weights.keySet
      n          = validators.size
      _          <- matrixRef.set(List.fill(n, n)(0))
      _          <- firstLevelZeroVotesRef.set(List.fill(n)(None))
      // Assigns numeric identifiers 0, ..., N-1 to all validators
      validatorsToIndex      = validators.zipWithIndex.toMap
      _                      <- validatorToIndexRef.set(validatorsToIndex)
      latestMessages         <- dag.latestMessages
      latestMessagesOfVoters = latestMessages.filterKeys(validators)
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
      firstLevelZeroMessagesAsList <- latestVoteValueOfVotesAsList
                                       .traverse {
                                         case (v, voteValue) =>
                                           FinalityDetectorUtil
                                             .levelZeroMsgsOfValidator(dag, v, voteValue)
                                             .map(
                                               _.lastOption
                                                 .map(b => (v, (b.blockHash, b.rank)))
                                             )
                                       }
      firstLevelZeroVotesAsMap = firstLevelZeroMessagesAsList.flatten.toMap
      firstLevelZeroVotes = fromMapToArray(
        validatorsToIndex,
        firstLevelZeroVotesAsMap.get
      )
      _ <- firstLevelZeroVotesRef.set(firstLevelZeroVotes)
      latestMessagesToUpdated = latestMessagesOfVoters.filterKeys(
        firstLevelZeroVotesAsMap.contains
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
      votingMatrix = new VotingMatrixImpl[F]()(
        Sync[F],
        matrixRef,
        firstLevelZeroVotesRef,
        validatorToIndexRef,
        weightMapRef
      )
    } yield votingMatrix
}
