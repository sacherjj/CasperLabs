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
 * Implementation inspired by The Inspector algorithm
 *
 * https://hackingresear.ch/cbc-inspector/
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

  /*
   * finding the best level 1 committee for a given candidate block
   */
  def findBestCommittee(
      blockDag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Option[Committee]]
}

object SafetyOracle {
  def apply[F[_]](implicit ev: SafetyOracle[F]): SafetyOracle[F] = ev

  case class Committee(validators: Set[Validator], bestQ: Long)
}

class SafetyOracleInstancesImpl[F[_]: Monad: Log] extends SafetyOracle[F] {

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
    ???

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

    def previousAgreedBlockFromTheSameValidator(
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
        continue <- OptionT(
                     computeCompatibility(
                       blockDag,
                       candidateBlockHash,
                       validatorLastLatestHash
                     ).map(_.some)
                   )
        creatorJustificationAsList = if (continue) {
          List(validatorLastLatestMsg)
        } else {
          List.empty[BlockMetadata]
        }
      } yield creatorJustificationAsList).fold(List.empty[BlockMetadata])(id)

    validators.foldLeftM(Map.empty[Validator, List[BlockMetadata]]) {
      case (acc, v) =>
        for {
          value <- levelZeroMsgsOfValidator(v)
        } yield acc.updated(v, value)
    }
  }

  def constructJDagFromLevelZeroMsgs(
      levelZeroMsgs: Map[Validator, List[BlockMetadata]]
  ): DoublyLinkedDag[BlockHash] =
    levelZeroMsgs.values.foldLeft(BlockDependencyDag.empty: DoublyLinkedDag[BlockHash]) {
      case (jDag, msgs) =>
        msgs.foldLeft(jDag) {
          case (jDag, msg) =>
            msg.justifications.foldLeft(jDag) {
              case (jDag, justification) =>
                DoublyLinkedDagOperations.add(
                  jDag,
                  parent = justification.latestBlockHash,
                  child = msg.blockHash
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
                    val levelKBlocks = blockLevelTags
                      .filter {
                        case (_, blockLevelTag) =>
                          blockLevelTag.blockLevel >= k
                      }
                    val minEstimateLevelKTag = levelKBlocks.minBy {
                      case (_, blockScoreTag) => blockScoreTag.estimateQ
                    }
                    val (_, minEstimate) = minEstimateLevelKTag

                    // The quorum of new pruned committee is the min support of all block having level >= k
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
                      // if there is no block bigger than minQ, then return directly
                      newCommittee.some.pure[F]
                    } else {
                      // prune validators
                      val prunedValidatorCandidates = prunedLevelKBlocks.map {
                        case (_, blockScoreAccumulator) =>
                          blockScoreAccumulator.block.validatorPublicKey
                      }.toSet
                      // Update q to be the (new) quorum of L_k.
                      val newQuorumOfPrunedL = prunedLevelKBlocks
                        .minBy {
                          case (_, blockScoreAccumulator) => blockScoreAccumulator.estimateQ
                        }
                        ._2
                        .estimateQ

                      pruningLoop(
                        blockDag,
                        jDag,
                        prunedValidatorCandidates,
                        levelZeroMsgs,
                        weightMap,
                        Some(newCommittee),
                        newQuorumOfPrunedL,
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
      weightMap: Map[Validator, Long]
  ): F[(Map[BlockHash, BlockScoreAccumulator], Map[Validator, Int])] = {
    val lowestLevelZeroMsgs = committeeApproximation
      .flatMap(v => levelZeroMsgs(v).lastOption)
      .toList
    val stream = DagOperations.bfToposortTraverseF(lowestLevelZeroMsgs)(
      b =>
        jDag.parentToChildAdjacencyList
          .getOrElse(b.blockHash, Set.empty)
          .toList
          .traverse(
            blockDag
              .lookup(_)
              .map(_.filter(b => committeeApproximation.contains(b.validatorPublicKey)))
          )
          .map(_.flatten)
    )

    val blockLevelTags =
      lowestLevelZeroMsgs.foldLeft(Map.empty[BlockHash, BlockScoreAccumulator]) {
        case (acc, b) =>
          acc + (b.blockHash -> BlockScoreAccumulator.empty(b))
      }

    val effectiveWeight: Validator => Long = (vid: Validator) =>
      if (committeeApproximation.contains(vid)) weightMap(vid) else 0L

    stream
      .foldLeftF((blockLevelTags, Map.empty[Validator, Int])) {
        case ((blockLevelTags, validatorLevel), b) =>
          val currentBlockScore = blockLevelTags(b.blockHash)
          val updatedBlockScore = BlockScoreAccumulator.updateOwnLevel(
            currentBlockScore,
            q,
            effectiveWeight
          )
          val updatedBlockLevelTags = blockLevelTags.updated(b.blockHash, updatedBlockScore)
          for {
            // after update current block's tag information,
            // we need update its children seen blocks's level information as well
            updatedBlockLevelTags <- jDag.parentToChildAdjacencyList
                                      .getOrElse(b.blockHash, Set.empty)
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
                                              .inheritFromParent(
                                                childBlockStore,
                                                updatedBlockScore
                                              )
                                          } yield acc.updated(child, updatedChildBlockStore)
                                      }
            vid                      = b.validatorPublicKey
            latestMessageOfValidator <- blockDag.latestMessage(vid).map(_.get)
            updatedValidatorLevel = if (committeeApproximation.contains(vid) && latestMessageOfValidator.blockHash == b.blockHash)
              validatorLevel + (vid -> updatedBlockScore.blockLevel)
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
                                                    q = maxWeightApproximation / 2
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
      effectiveWeight: Validator => Long
  ): BlockScoreAccumulator =
    calculateLevelAndQ(self, self.highestLevelSoFar + 1, q, effectiveWeight)
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
