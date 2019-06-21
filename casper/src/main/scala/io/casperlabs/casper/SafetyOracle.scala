package io.casperlabs.casper

import cats.Monad
import cats.data.OptionT
import cats.implicits._
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockMetadata}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.SafetyOracle.Committee
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.ski.id
import io.casperlabs.shared.{Log, StreamT}

/*
 * Implementation inspired by Ethereum's CBC casper simulator's clique oracle implementation.
 *
 * https://github.com/ethereum/cbc-casper/blob/0.2.0/casper/safety_oracles/clique_oracle.py
 *
 * "If nodes in an e-clique see each other agreeing on e and can't see each other disagreeing on e,
 * then there does not exist any new message from inside the clique that will cause them to assign
 * lower scores to e. Further, if the clique has more than half of the validators by weight,
 * then no messages external to the clique can raise the scores these validators assign to
 * a competing [candidate] to be higher than the score they assign to e."
 *
 * - From https://github.com/ethereum/research/blob/master/papers/CasperTFG/CasperTFG.pdf
 *
 * That is unless there are equivocations.
 * The fault tolerance threshold is a subjective value that the user sets to "secretly" state that they
 * tolerate up to fault_tolerance_threshold fraction of the total weight to equivocate.
 *
 * In the extreme case when your normalized fault tolerance threshold is 1,
 * all validators must be part of the clique that supports the candidate in order to state that it is finalized.
 */
trait SafetyOracle[F[_]] {

  /**
    * The normalizedFaultTolerance must be greater than the fault tolerance threshold t in order
    * for a candidate to be safe.
    *
    * @param candidateBlockHash Block hash of candidate block to detect safety on
    * @return normalizedFaultTolerance float between -1 and 1, where -1 means potentially orphaned
    */
  def normalizedFaultTolerance(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Float]

  def findBestCommittee(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Option[Committee]]
}

object SafetyOracle extends SafetyOracleInstances {
  def apply[F[_]](implicit ev: SafetyOracle[F]): SafetyOracle[F] = ev

  case class Committee(validators: Set[Validator], bestQ: Long)
}

sealed abstract class SafetyOracleInstances {
  def cliqueOracle[F[_]: Monad: Log]: SafetyOracle[F] =
    new SafetyOracle[F] {

      /**
        * To have a maximum clique of half the total weight,
        * you need at least twice the weight of the agreeingValidatorToWeight to be greater than the total weight.
        * If that is false, we don't need to compute agreementGraphMaxCliqueWeight
        * as we know the value is going to be below 0 and thus useless for finalization.
        */
      def normalizedFaultTolerance(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash
      ): F[Float] =
        for {
          totalWeight <- computeTotalWeight(blockDag, candidateBlockHash)
          agreeingValidatorToWeight <- computeAgreeingValidatorToWeight(
                                        blockDag,
                                        candidateBlockHash
                                      )
          maxCliqueWeight <- if (2L * agreeingValidatorToWeight.values.sum < totalWeight) {
                              0L.pure[F]
                            } else {
                              agreementGraphMaxCliqueWeight(
                                blockDag,
                                candidateBlockHash,
                                agreeingValidatorToWeight
                              )
                            }
          faultTolerance = 2 * maxCliqueWeight - totalWeight
        } yield faultTolerance.toFloat / totalWeight

      private def computeTotalWeight(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash
      ): F[Long] =
        computeMainParentWeightMap(blockDag, candidateBlockHash).map(weightMapTotal)

      private def computeAgreeingValidatorToWeight(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash
      ): F[Map[Validator, Long]] =
        for {
          weights <- computeMainParentWeightMap(blockDag, candidateBlockHash)
          agreeingWeights <- weights.toList.traverse {
                              case (validator, stake) =>
                                blockDag.latestMessageHash(validator).flatMap {
                                  case Some(latestMessageHash) =>
                                    computeCompatibility(
                                      blockDag,
                                      candidateBlockHash,
                                      latestMessageHash
                                    ).map { isCompatible =>
                                      if (isCompatible) {
                                        Some((validator, stake))
                                      } else {
                                        none[(Validator, Long)]
                                      }
                                    }
                                  case None =>
                                    none[(Validator, Long)].pure[F]
                                }
                            }
        } yield agreeingWeights.flatten.toMap

      private def computeMainParentWeightMap(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash
      ): F[Map[BlockHash, Long]] =
        blockDag.lookup(candidateBlockHash).flatMap { blockOpt =>
          blockOpt.get.parents.headOption match {
            case Some(parent) => blockDag.lookup(parent).map(_.get.weightMap)
            case None         => blockDag.lookup(candidateBlockHash).map(_.get.weightMap)
          }
        }

      private def agreementGraphMaxCliqueWeight(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash,
          agreeingValidatorToWeight: Map[Validator, Long]
      ): F[Long] = {
        def filterChildren(block: BlockMetadata, validator: Validator): F[StreamT[F, BlockHash]] =
          blockDag.latestMessageHash(validator).flatMap {
            case Some(latestByValidatorHash) =>
              val creatorJustificationOrGenesis = block.justifications
                .find(_.validatorPublicKey == block.validatorPublicKey)
                .fold(block.blockHash)(_.latestBlockHash)
              DagOperations
                .bfTraverseF[F, BlockHash](List(latestByValidatorHash)) { blockHash =>
                  ProtoUtil.getCreatorJustificationAsListUntilGoalInMemory(
                    blockDag,
                    blockHash,
                    validator,
                    b => b == creatorJustificationOrGenesis
                  )
                }
                .pure[F]
            case None => StreamT.empty[F, BlockHash].pure[F]
          }

        def neverEventuallySeeDisagreement(
            first: Validator,
            second: Validator
        ): F[Boolean] =
          (for {
            firstLatestBlock <- OptionT(blockDag.latestMessage(first))
            secondLatestOfFirstLatestHash <- OptionT.fromOption[F](
                                              firstLatestBlock.justifications
                                                .find {
                                                  case Justification(validator, _) =>
                                                    validator == second
                                                }
                                                .map(_.latestBlockHash)
                                            )
            secondLatestOfFirstLatest <- OptionT(blockDag.lookup(secondLatestOfFirstLatestHash))
            potentialDisagreements <- OptionT.liftF(
                                       filterChildren(secondLatestOfFirstLatest, second)
                                     )
            // TODO: Implement forallM on StreamT
            result <- OptionT.liftF(potentialDisagreements.toList.flatMap(_.forallM {
                       potentialDisagreement =>
                         computeCompatibility(blockDag, candidateBlockHash, potentialDisagreement)
                     }))
          } yield result).fold(false)(id)

        def computeAgreementGraphEdges: F[List[(Validator, Validator)]] =
          (for {
            x <- agreeingValidatorToWeight.keys
            y <- agreeingValidatorToWeight.keys
            if x.toString > y.toString // TODO: Order ByteString
          } yield (x, y)).toList.filterA {
            case (first: Validator, second: Validator) =>
              neverEventuallySeeDisagreement(first, second) &&^ neverEventuallySeeDisagreement(
                second,
                first
              )
          }

        computeAgreementGraphEdges.map { edges =>
          Clique.findMaximumCliqueByWeight[Validator](edges, agreeingValidatorToWeight)
        }
      }

      // If targetBlockHash is main descendant of candidateBlockHash, then
      // it means targetBlockHash vote candidateBlockHash.
      private def computeCompatibility(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash,
          targetBlockHash: BlockHash
      ): F[Boolean] =
        isInMainChain(blockDag, candidateBlockHash, targetBlockHash)

      private def levelZeroMsgs(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash,
          validators: List[Validator]
      ): F[Map[Validator, List[BlockMetadata]]] = {

        // Get level zero messages of validator A
        def levelZeroMsgsOfValidator(
            validator: Validator
        ): F[List[BlockMetadata]] =
          blockDag.latestMessage(validator).flatMap {
            case Some(latestMsgByValidator) =>
              for {
                l <- DagOperations
                      .bfTraverseF[F, BlockMetadata](List(latestMsgByValidator))(
                        previousAgreedBlockFromTheSameValidator(
                          blockDag,
                          _,
                          candidateBlockHash,
                          validator
                        )
                      )
                      .toList
              } yield l
            case None => List.empty[BlockMetadata].pure[F]
          }

        validators.foldLeftM(Map.empty[Validator, List[BlockMetadata]]) {
          case (acc, v) =>
            for {
              value <- levelZeroMsgsOfValidator(v)
            } yield acc.updated(v, value)
        }
      }

      private def previousAgreedBlockFromTheSameValidator(
          blockDag: BlockDagRepresentation[F],
          block: BlockMetadata,
          candidateBlockHash: BlockHash,
          validator: Validator
      ): F[List[BlockMetadata]] =
        (for {
          validatorLastLatestHash <- OptionT.fromOption[F](
                                      block.justifications
                                        .find(
                                          _.validatorPublicKey == validator
                                        )
                                        .map(_.latestBlockHash)
                                    )
          validatorLastLatestMsg <- OptionT(blockDag.lookup(validatorLastLatestHash))
          end <- OptionT(
                  computeCompatibility(
                    blockDag,
                    candidateBlockHash,
                    validatorLastLatestHash
                  ).map(t => (!t).some)
                )
          creatorJustificationAsList = if (end) {
            List.empty[BlockMetadata]
          } else {
            List(validatorLastLatestMsg)
          }
        } yield creatorJustificationAsList).fold(List.empty[BlockMetadata])(id)

      private def constructJDagFromLevelZeroMsgs(
          levelZeroMsgs: Map[Validator, List[BlockMetadata]],
          prunedMsgs: Set[BlockHash] = Set.empty[BlockHash]
      ): DoublyLinkedDag[BlockHash] =
        levelZeroMsgs.values.foldLeft(BlockDependencyDag.empty: DoublyLinkedDag[BlockHash]) {
          case (jDag, msgs) =>
            msgs.foldLeft(jDag) {
              case (jDag, msg) =>
                msg.justifications.foldLeft(jDag) {
                  case (jDag, justification) =>
                    DoublyLinkedDagOperations.add(
                      jDag,
                      justification.latestBlockHash,
                      msg.blockHash
                    )
                }
            }
        }

      private def pruningLoop(
          blockDag: BlockDagRepresentation[F],
          jDag: DoublyLinkedDag[BlockHash],
          committeeApproximation: Set[Validator],
          levelZeroMsgs: Map[Validator, List[BlockMetadata]],
          weightMap: Map[Validator, Long],
          lastCommittee: Option[Committee],
          q: Long,
          k: Int = 1
      ): F[Option[Committee]] =
        for {
          sweepResult <- sweep(
                          blockDag,
                          jDag,
                          committeeApproximation,
                          levelZeroMsgs,
                          q,
                          weightMap
                        )
          (blockLevelTags, committeeLevels) = sweepResult
          prunedCommittee = committeeLevels
            .filter { case (_, level) => level >= k }
            .keys
            .toSet
          committee <- if (prunedCommittee.isEmpty) {
                        // If L is empty, return qbest
                        lastCommittee.pure[F]
                      } else {
                        // Otherwise set qbest to newCommittee.
                        val levelKBlocks = blockLevelTags
                          .filter {
                            case (_, blockLevelTag) =>
                              BlockScoreAccumulator.ownLevel(blockLevelTag) >= k
                          }
                        val minEstimateLevelKTag = levelKBlocks.minBy {
                          case (_, blockScoreTag) => blockScoreTag.estimateQ
                        }
                        val (_, minEstimate) = minEstimateLevelKTag

                        val newCommittee = Committee(
                          prunedCommittee,
                          minEstimate.estimateQ
                        )

                        // split all blocks from L_k by whether its support exactly equal to minQ.
                        val (toRemoved, prunedLevelKBlocks) = levelKBlocks.partition {
                          case (_, blockScoreTag) =>
                            blockScoreTag.estimateQ == minEstimate.estimateQ
                        }
                        if (prunedLevelKBlocks.isEmpty) {
                          // if there is no blocks bigger than minQ, than return directly
                          newCommittee.some.pure[F]
                        } else {
                          // prune validators
                          val prunedValidatorCandidates = prunedLevelKBlocks.map {
                            case (_, blockScoreAccumulator) =>
                              blockScoreAccumulator.block.validatorPublicKey
                          }.toSet
                          pruningLoop(
                            blockDag,
                            jDag,
                            prunedValidatorCandidates,
                            levelZeroMsgs,
                            weightMap,
                            Some(newCommittee),
                            minEstimate.estimateQ,
                            k
                          )
                        }
                      }
        } yield committee

      private def sweep(
          blockDag: BlockDagRepresentation[F],
          jDag: DoublyLinkedDag[BlockHash],
          committeeApproximation: Set[Validator],
          levelZeroMsgs: Map[Validator, List[BlockMetadata]],
          q: Long,
          weightMap: Map[Validator, Long]
      ): F[(Map[BlockHash, BlockScoreAccumulator], Map[Validator, Int])] = {
        val lowestLevelZeroMsgs = committeeApproximation
          .flatMap(v => levelZeroMsgs(v).lastOption)
          .toList
        val stream = DagOperations.bfToposortTraverseF(lowestLevelZeroMsgs)(
          b =>
            jDag
              .parentToChildAdjacencyList(b.blockHash)
              .toList
              .traverse(blockDag.lookup)
              .map(_.flatten)
        )

        val blockLevelTags =
          lowestLevelZeroMsgs.foldLeft(Map.empty[BlockHash, BlockScoreAccumulator]) {
            case (acc, b) =>
              acc + (b.blockHash -> BlockScoreAccumulator.empty(b))
          }

        stream
          .foldLeftF((blockLevelTags, Map.empty[Validator, Int])) {
            case ((blockLevelTags, validatorLevel), b) =>
              val currentBlockScore: BlockScoreAccumulator = blockLevelTags(b.blockHash)
              val updatedBlockScore = BlockScoreAccumulator.updateOwnLevel(
                currentBlockScore,
                q,
                committeeApproximation,
                weightMap
              )
              val updatedBlockLevelTags = blockLevelTags.updated(b.blockHash, updatedBlockScore)
              for {
                updatedBlockLevelTags <- jDag
                                          .parentToChildAdjacencyList(b.blockHash)
                                          .toList
                                          .foldLeftM(updatedBlockLevelTags) {
                                            case (acc, child) =>
                                              for {
                                                childBlock <- blockDag.lookup(child).map(_.get)
                                                childBlockStore = acc
                                                  .getOrElse(
                                                    child,
                                                    BlockScoreAccumulator.empty(childBlock)
                                                  )
                                                updatedChildBlockStore = BlockScoreAccumulator
                                                  .addParent(
                                                    childBlockStore,
                                                    updatedBlockScore
                                                  )
                                              } yield acc.updated(child, updatedBlockScore)
                                          }
                vid                      = b.validatorPublicKey
                latestMessageOfValidator <- blockDag.latestMessage(vid).map(_.get)
                updatedValidatorLevel = if (committeeApproximation.contains(vid) && latestMessageOfValidator.blockHash == b.blockHash)
                  validatorLevel + (vid -> BlockScoreAccumulator.ownLevel(currentBlockScore))
                else validatorLevel
              } yield (updatedBlockLevelTags, updatedValidatorLevel)
          }
      }

      def findBestCommittee(
          blockDag: BlockDagRepresentation[F],
          candidateBlockHash: BlockHash
      ): F[Option[Committee]] =
        for {
          weights     <- computeMainParentWeightMap(blockDag, candidateBlockHash)
          totalWeight = weights.values.sum
          committeeApproximation <- weights.keys.toList.filterA(
                                     computeCompatibility(blockDag, candidateBlockHash, _)
                                   )
          maxWeightApproximation = committeeApproximation.map(weights).sum
          result <- if (2 * maxWeightApproximation < totalWeight) {
                     none[Committee].pure[F]
                   } else {
                     for {
                       levelZeroMsgs <- levelZeroMsgs(
                                         blockDag,
                                         candidateBlockHash,
                                         committeeApproximation
                                       )
                       jDag = constructJDagFromLevelZeroMsgs(levelZeroMsgs)

                       committeeMembersAfterPruning <- pruningLoop(
                                                        blockDag,
                                                        jDag,
                                                        committeeApproximation.toSet,
                                                        levelZeroMsgs,
                                                        weights,
                                                        None,
                                                        maxWeightApproximation / 2
                                                      )
                     } yield committeeMembersAfterPruning
                   }
        } yield result

    }

  /**
		* We attach an instance of BlockScoreAccumulator to every block in the jDAG.
		* As we traverse the jDag bottom-up (following the topological sorting). we accumulate the view of what is "seen"
		* from the perspective of this block (as a map validator ---> highest level of his blocks seen in j-past-cone).
		* Here "level" is what Inspector Finality Detector calls a level.
		*/
  case class BlockScoreAccumulator(
      block: BlockMetadata,
      highestLevelBySeenBlocks: Map[Validator, Int],
      estimateQ: Long,
      highestLevelSoFar: Int = 0
  )
  object BlockScoreAccumulator {
    def empty(block: BlockMetadata): BlockScoreAccumulator =
      BlockScoreAccumulator(block, Map.empty, 0, 0)

    def addParent(
        self: BlockScoreAccumulator,
        parent: BlockScoreAccumulator
    ): BlockScoreAccumulator = {
      val (highestLevelSoFar, highestLevelBySeenBlocks) =
        parent.highestLevelBySeenBlocks
          .foldLeft(self.highestLevelSoFar -> self.highestLevelBySeenBlocks) {
            case ((highestLevel, acc), (vid, level)) =>
              val oldLevel = acc.getOrElse(vid, -1)
              val newAcc =
                if (oldLevel < level)
                  acc.updated(vid, level)
                else
                  acc
              math.max(highestLevel, level) -> newAcc
          }
      BlockScoreAccumulator(self.block, highestLevelBySeenBlocks, self.estimateQ, highestLevelSoFar)
    }

    def updateOwnLevel(
        self: BlockScoreAccumulator,
        q: Long,
        committeeApproximation: Set[Validator],
        validatorsWeightMap: Map[Validator, Long]
    ): BlockScoreAccumulator = {
      val effectiveWeight: Validator => Long = (vid: Validator) =>
        if (committeeApproximation.contains(vid)) validatorsWeightMap(vid) else 0L
      calculateLevelAndQ(self, self.highestLevelSoFar, q, effectiveWeight)
        .map {
          case (level, estimateQ) =>
            val newMaxLevel = math.max(self.highestLevelSoFar, level)
            BlockScoreAccumulator(self.block, self.highestLevelBySeenBlocks, estimateQ, newMaxLevel)
        }
        .getOrElse(self)
    }

    def ownLevel(self: BlockScoreAccumulator): Int =
      self.highestLevelBySeenBlocks(self.block.validatorPublicKey)

    // Though we only want to find best level 1 committee, this algorithm can calculate level k in one pass
    private def calculateLevelAndQ(
        self: BlockScoreAccumulator,
        k: Int,
        q: Long,
        effectiveWeight: Validator => Long
    ): Option[(Int, Long)] =
      if (k == 0) {
        None
      } else {
        val totalWeightOfSupporters: Long = (self.highestLevelBySeenBlocks map {
          case (vid, level) =>
            if (level >= k) effectiveWeight(vid) else 0L
        }).sum
        if (totalWeightOfSupporters >= q) {
          Some(k + 1, totalWeightOfSupporters)
        } else {
          calculateLevelAndQ(self, k - 1, q, effectiveWeight)
        }
      }
  }
}
