package io.casperlabs.casper

import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.FinalityDetector.Committee
import io.casperlabs.casper.util.ProtoUtil
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

object VotingMatrixImpl {
  // (consensus value, dagLevel of the block)
  type Vote = (BlockHash, Long)
  def of[F[_]: Concurrent]: F[VotingMatrix[F]] =
    for {
      matrixRef              <- Ref.of[F, List[List[Long]]](List.empty)
      firstLevelZeroVotesRef <- Ref.of[F, List[Option[Vote]]](List.empty)
      validatorToIndexRef    <- Ref.of[F, Map[Validator, Int]](Map.empty)
      weightMapRef           <- Ref.of[F, Map[Validator, Long]](Map.empty)
    } yield {
      new VotingMatrix[F] {
        override def updateVoterPerspective(
            dag: DagRepresentation[F],
            blockMetadata: BlockMetadata,
            currentVoteValue: BlockHash
        ): F[Unit] =
          for {
            _                   <- updateVotingMatrixOnNewBlock(dag, blockMetadata)
            voter               = blockMetadata.validatorPublicKey
            currentDagLevel     = blockMetadata.rank
            firstLevelZeroVotes <- firstLevelZeroVotesRef.get
            validatorToIndex    <- validatorToIndexRef.get
            indexOfVoter        = validatorToIndex(voter)
            rowOpt              = firstLevelZeroVotes(indexOfVoter)
            _ = rowOpt match {
              case Some((finalVoteValue, _)) =>
                if (finalVoteValue != currentVoteValue)
                  firstLevelZeroVotesRef.update(
                    l => l.updated(indexOfVoter, (currentVoteValue, currentDagLevel).some)
                  )
              case None =>
                firstLevelZeroVotesRef.update(
                  l => l.updated(indexOfVoter, (currentVoteValue, currentDagLevel).some)
                )
            }
          } yield ()

        private def updateVotingMatrixOnNewBlock(
            dag: DagRepresentation[F],
            blockMetadata: BlockMetadata
        ): F[Unit] =
          for {
            weightMap        <- weightMapRef.get
            validatorToIndex <- validatorToIndexRef.get
            validators       = weightMap.keySet
            latestBlockDagLevelsAsMap <- FinalityDetectorUtil.panoramaDagLevelsOfBlock(
                                          dag,
                                          blockMetadata,
                                          validators
                                        )
            latestBlockDagLevels = fromMapToArray(
              validatorToIndex,
              latestBlockDagLevelsAsMap.getOrElse(_, 0L)
            ) //if not, set to daglevel of Genesis block
            _ <- matrixRef.update(
                  matrix =>
                    matrix.updated(
                      validatorToIndex(blockMetadata.validatorPublicKey),
                      latestBlockDagLevels
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
            committeeOpt = pruneToCommittee(
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
        private def pruneToCommittee(
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
                val count = firstLevelZeroVotes.zipWithIndex.map {
                  case (Some((consensusValue, dagLevel)), columnIndex) =>
                    if (consensusValue == candidateBlockHash && dagLevel <= row(columnIndex))
                      weight(columnIndex)
                    else 0L
                  case _ => 0L
                }.sum
                if (count >= q) {
                  (newMask, prunedValidator, maxTotalWeight + weight(rowIndex))
                } else {
                  (newMask.updated(rowIndex, false), true, maxTotalWeight)
                }
            }
          if (prunedValidator) {
            if (maxTotalWeight < q)
              None
            else
              pruneToCommittee(matrix, firstLevelZeroVotes, candidateBlockHash, newMask, q, weight)
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
            weights               <- ProtoUtil.mainParentWeightMap(dag, newFinalizedBlock)
            _                     <- weightMapRef.set(weights)
            validators            = weights.keySet
            validatorsToIndex     = validators.zipWithIndex.toMap
            _                     <- validatorToIndexRef.set(validatorsToIndex)
            latestMessages        <- dag.latestMessages
            latestMessagesOfVotes = latestMessages.filterKeys(validators)
            latestVoteValueOfVotesAsList <- latestMessagesOfVotes.toList
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
            // Traverse down the swimlane of V(i) to find the earliest block voting for the same Fm's child
            // as V(i) latest does. This way we can initialize first-zero-level-messages(i).
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
            // Apply the incremental update step from previous chapter (but only steps 1...3) taking M := V(i)latest
            _ <- latestMessagesOfVotes.values.toList.traverse { b =>
                  updateVotingMatrixOnNewBlock(dag, b)
                }
          } yield ()

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
    }
}
