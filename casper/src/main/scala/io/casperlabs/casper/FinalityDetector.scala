package io.casperlabs.casper

import cats.Monad
import cats.data.OptionT
import cats.implicits._
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockMetadata}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.FinalityDetector.Committee
import io.casperlabs.casper.util.DagOperations.Key.blockMetadataKey
import io.casperlabs.catscontrib.ski.id
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.shared.Log

/*
 * Implementation inspired by The Inspector algorithm
 *
 * https://hackingresear.ch/cbc-inspector/
 */
trait FinalityDetector[F[_]] {

  /**
    * The normalizedFaultTolerance must be greater than
		* the fault tolerance threshold t in order for a candidate to be safe.
		* The range of t is [-1,1], and a positive t means the fraction of validators would have
		* to equivocate to revert the decision on the block, a negative t means unless that fraction
		* equivocates, the block can't get finalized. (I.e. it's orphaned.)
		*
		* @param candidateBlockHash Block hash of candidate block to detect safety on
    * @return normalizedFaultTolerance float between -1 and 1,
		*         where
    */
  def normalizedFaultTolerance(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Float]
}

object FinalityDetector {
  def apply[F[_]](implicit ev: FinalityDetector[F]): FinalityDetector[F] = ev

  case class Committee(validators: Set[Validator], bestQ: Long)
}

class FinalityDetectorInstancesImpl[F[_]: Monad: Log: MonadThrowable] extends FinalityDetector[F] {

  /**
    * To have a committee of half the total weight,
    * you need at least twice the weight of the agreeingValidatorToWeight to be greater than the total weight.
    * If that is false, we don't need to compute best committee
    * as we know the value is going to be below 0 and thus useless for finalization.
    */
  def normalizedFaultTolerance(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Float] =
    for {
      weights      <- computeMainParentWeightMap(blockDag, candidateBlockHash)
      committeeOpt <- findBestCommittee(blockDag, candidateBlockHash, weights)
      t = committeeOpt
        .map(committee => {
          val totalWeight = weights.values.sum
          (2 * committee.bestQ - totalWeight) * 1.0f / (2 * totalWeight)
        })
        .getOrElse(0f)
    } yield t

  def computeMainParentWeightMap(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Map[BlockHash, Long]] =
    blockDag.lookup(candidateBlockHash).flatMap { blockOpt =>
      blockOpt.get.parents.headOption match {
        case Some(parent) => blockDag.lookup(parent).map(_.get.weightMap)
        case None         => blockDag.lookup(candidateBlockHash).map(_.get.weightMap)
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

  def levelZeroMsgs(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash,
      validators: List[Validator]
  ): F[Map[Validator, List[BlockMetadata]]] = {

    // Get level zero messages of the specified validator
    def levelZeroMsgsOfValidator(
        validator: Validator
    ): F[List[BlockMetadata]] =
      blockDag.latestMessage(validator).flatMap {
        case Some(latestMsgByValidator) =>
          DagOperations
            .bfTraverseF[F, BlockMetadata](List(latestMsgByValidator))(
              previousAgreedBlockFromTheSameValidator(
                blockDag,
                _,
                candidateBlockHash,
                validator
              )
            )
            .toList
        case None => List.empty[BlockMetadata].pure[F]
      }

    def previousAgreedBlockFromTheSameValidator(
        blockDag: BlockDagRepresentation[F],
        block: BlockMetadata,
        candidateBlockHash: BlockHash,
        validator: Validator
    ): F[List[BlockMetadata]] =
      (for {
        previousHash <- OptionT.fromOption[F](
                         block.justifications
                           .find(
                             _.validatorPublicKey == validator
                           )
                           .map(_.latestBlockHash)
                       )
        previousMsg <- OptionT(
                        blockDag
                          .lookup(previousHash)
                          .map(_.filter(_.validatorPublicKey == validator))
                      )
        continue <- OptionT(
                     computeCompatibility(
                       blockDag,
                       candidateBlockHash,
                       previousHash
                     ).map(_.some)
                   )
        previousMsgs = if (continue) {
          List(previousMsg)
        } else {
          List.empty[BlockMetadata]
        }
      } yield previousMsgs).fold(List.empty[BlockMetadata])(id)

    validators.foldLeftM(Map.empty[Validator, List[BlockMetadata]]) {
      case (acc, v) =>
        for {
          value <- levelZeroMsgsOfValidator(v)
        } yield acc.updated(v, value)
    }
  }

  def constructJDagFromLevelZeroMsgs(
      levelZeroMsgs: Map[Validator, List[BlockMetadata]]
  ): DoublyLinkedDag[BlockHash] = {
    val msgs   = levelZeroMsgs.values.flatten
    val msgSet = msgs.map(_.blockHash).toSet
    msgs.foldLeft(BlockDependencyDag.empty: DoublyLinkedDag[BlockHash]) {
      case (jDag, msg) =>
        msg.justifications.foldLeft(jDag) {
          case (jDag, justification) =>
            if (msgSet.contains(justification.latestBlockHash)) {
              DoublyLinkedDagOperations.add(
                jDag,
                parent = justification.latestBlockHash,
                child = msg.blockHash
              )
            } else {
              jDag
            }
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
                      k,
                      weightMap
                    )
      (blockLevelTags, validatorLevels) = sweepResult
      prunedCommittee = validatorLevels
        .filter { case (_, level) => level >= k }
        .keys
        .toSet
      committee <- if (prunedCommittee.isEmpty) {
                    // If L is empty, return qbest
                    lastCommittee.pure[F]
                  } else {
                    // Otherwise set qbest to newCommittee.
                    // The quorum of new pruned committee is the minimal support
                    // of all block having level >= 1
                    val levelAbove1Tags = blockLevelTags
                      .filter {
                        case (_, blockLevelTag) =>
                          blockLevelTag.blockLevel >= 1
                      }
                    val minEstimateLevelKTag = levelAbove1Tags.minBy {
                      case (_, blockScoreTag) => blockScoreTag.estimateQ
                    }
                    val (_, minEstimate) = minEstimateLevelKTag

                    val newCommittee = Committee(
                      prunedCommittee,
                      minEstimate.estimateQ
                    )

                    // split all blocks from L_1 by whether its support exactly equal to minQ.
                    val (_, prunedLevel1Blocks) = levelAbove1Tags.partition {
                      case (_, blockScoreTag) =>
                        blockScoreTag.estimateQ == minEstimate.estimateQ
                    }
                    if (prunedLevel1Blocks.isEmpty) {
                      // if there is no block bigger than minQ, then return directly
                      newCommittee.some.pure[F]
                    } else {
                      // prune validators
                      val prunedValidatorCandidates = prunedLevel1Blocks
                        .filter {
                          case (_, blockScoreAccumulator) =>
                            blockScoreAccumulator.blockLevel >= k
                        }
                        .map {
                          case (_, blockScoreAccumulator) =>
                            blockScoreAccumulator.block.validatorPublicKey
                        }
                        .toSet

                      pruningLoop(
                        blockDag,
                        jDag,
                        prunedValidatorCandidates,
                        levelZeroMsgs,
                        weightMap,
                        Some(newCommittee),
                        minEstimate.estimateQ + 1L,
                        k
                      )
                    }
                  }
    } yield committee

  // Tag level information for each block in one-pass
  def sweep(
      blockDag: BlockDagRepresentation[F],
      jDag: DoublyLinkedDag[BlockHash],
      committeeApproximation: Set[Validator],
      levelZeroMsgs: Map[Validator, List[BlockMetadata]],
      q: Long,
      k: Int,
      weightMap: Map[Validator, Long]
  ): F[(Map[BlockHash, BlockScoreAccumulator], Map[Validator, Int])] = {
    val toposortedBlockHashesOpt: Option[List[BlockHash]] = DagOperations.topologySort(jDag)

    val effectiveWeight: Validator => Long = (vid: Validator) =>
      if (committeeApproximation.contains(vid)) weightMap(vid) else 0L

    toposortedBlockHashesOpt match {
      case Some(blockHashes) =>
        blockHashes.foldLeftM(
          (Map.empty[BlockHash, BlockScoreAccumulator], Map.empty[Validator, Int])
        ) {
          case (nochanged @ (blockLevelTags, validatorLevel), b) =>
            for {
              blockMetadata <- blockDag.lookup(b)
              result <- if (!committeeApproximation.contains(blockMetadata.get.validatorPublicKey)) {
                         nochanged.pure[F]
                       } else {
                         val currentBlockScore =
                           blockLevelTags.getOrElse(
                             b,
                             BlockScoreAccumulator.empty(blockMetadata.get)
                           )
                         val updatedBlockScore = BlockScoreAccumulator.updateOwnLevel(
                           currentBlockScore,
                           q,
                           k,
                           effectiveWeight
                         )
                         val updatedBlockLevelTags = blockLevelTags.updated(b, updatedBlockScore)
                         for {
                           // after update current block's tag information,
                           // we need update its children seen blocks's level information as well
                           updatedBlockLevelTags <- jDag.parentToChildAdjacencyList
                                                     .getOrElse(b, Set.empty)
                                                     .toList
                                                     .foldLeftM(updatedBlockLevelTags) {
                                                       case (acc, child) =>
                                                         for {
                                                           childBlock <- blockDag
                                                                          .lookup(child)
                                                                          .map(_.get)
                                                           childBlockStore = acc
                                                             .getOrElse(
                                                               child,
                                                               BlockScoreAccumulator
                                                                 .empty(childBlock)
                                                             )
                                                           updatedChildBlockStore = BlockScoreAccumulator
                                                             .inheritFromParent(
                                                               childBlockStore,
                                                               updatedBlockScore
                                                             )
                                                         } yield acc.updated(
                                                           child,
                                                           updatedChildBlockStore
                                                         )
                                                     }
                           vid = blockMetadata.get.validatorPublicKey
                           maxLevel = math.max(
                             validatorLevel.getOrElse(vid, 0),
                             updatedBlockScore.blockLevel
                           )
                           updatedValidatorLevel = validatorLevel.updated(vid, maxLevel)
                         } yield (updatedBlockLevelTags, updatedValidatorLevel)
                       }
            } yield result
        }

      // should never happen
      case None =>
        Log[F].error("find a cycle in the dag") *> MonadThrowable[F].raiseError(
          new RuntimeException
        )
    }

  }

  /* finding the best level 1 committee for a given candidate block */
  def findBestCommittee(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[Option[Committee]] =
    for {
      committeeApproximation <- weights.keys.toList.filterA {
                                 case validator =>
                                   for {
                                     latestMessageHash <- blockDag
                                                           .latestMessageHash(
                                                             validator
                                                           )
                                     result <- latestMessageHash match {
                                                case Some(b) =>
                                                  computeCompatibility(
                                                    blockDag,
                                                    candidateBlockHash,
                                                    b
                                                  )
                                                case _ => false.pure[F]
                                              }
                                   } yield result

                               }
      totalWeight            = weights.values.sum
      maxWeightApproximation = committeeApproximation.map(weights).sum
      result <- if (2 * maxWeightApproximation <= totalWeight) {
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
                                                    q = maxWeightApproximation / 2
                                                  )
                 } yield committeeMembersAfterPruning
               }
    } yield result

}

/**
  * We attach an instance of BlockScoreAccumulator to every block in the jDAG.
  * As we traverse the jDag bottom-up (following the topological sorting), we accumulate the view of what is "seen"
  * from the perspective of this block (as a map validator ---> highest level of his blocks seen in j-past-cone).
  * Here "level" is what Inspector Finality Detector calls a level.
  */
case class BlockScoreAccumulator(
    block: BlockMetadata,
    highestLevelBySeenBlocks: Map[Validator, Int],
    estimateQ: Long,
    blockLevel: Int,
    highestLevelSoFar: Int = 0
)

object BlockScoreAccumulator {
  def empty(block: BlockMetadata): BlockScoreAccumulator =
    BlockScoreAccumulator(block, Map.empty, 0, 0)

  // children will inherit seen blocks from the parent
  def inheritFromParent(
      self: BlockScoreAccumulator,
      parent: BlockScoreAccumulator
  ): BlockScoreAccumulator = {
    val (highestLevelSoFar, highestLevelBySeenBlocks) =
      parent.highestLevelBySeenBlocks
        .foldLeft((self.highestLevelSoFar, self.highestLevelBySeenBlocks)) {
          case ((highestLevel, acc), (vid, level)) =>
            val oldLevel = acc.getOrElse(vid, -1)
            val newAcc =
              if (oldLevel < level)
                acc.updated(vid, level)
              else
                acc
            math.max(highestLevel, level) -> newAcc
        }
    val addParentSelf =
      if (highestLevelBySeenBlocks.getOrElse(parent.block.validatorPublicKey, -1) < parent.blockLevel)
        highestLevelBySeenBlocks.updated(parent.block.validatorPublicKey, parent.blockLevel)
      else
        highestLevelBySeenBlocks
    BlockScoreAccumulator(
      self.block,
      addParentSelf,
      self.estimateQ,
      self.blockLevel,
      highestLevelSoFar
    )
  }

  def updateOwnLevel(
      self: BlockScoreAccumulator,
      q: Long,
      k: Int,
      effectiveWeight: Validator => Long
  ): BlockScoreAccumulator =
    calculateLevelAndQ(self, math.min(self.highestLevelSoFar + 1, k), q, effectiveWeight)
      .map {
        case (level, estimateQ) =>
          val newMaxLevel = math.max(self.highestLevelSoFar, level)
          BlockScoreAccumulator(
            self.block,
            self.highestLevelBySeenBlocks,
            estimateQ,
            level,
            newMaxLevel
          )
      }
      .getOrElse(self)

  // Though we only want to find best level 1 committee,
  // this algorithm can calculate level k in one pass
  // Support of level K is smaller or equal to support of level 1 to level K-1
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
          if (level >= k - 1)
            effectiveWeight(vid)
          else
            0L
      }).sum
      if (totalWeightOfSupporters >= q) {
        Some(k, totalWeightOfSupporters)
      } else {
        calculateLevelAndQ(self, k - 1, q, effectiveWeight)
      }
    }
}
