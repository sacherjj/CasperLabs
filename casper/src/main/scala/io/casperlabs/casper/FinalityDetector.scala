package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.FinalityDetector.Committee
import io.casperlabs.casper.util.DagOperations.Key.blockMetadataKey
import io.casperlabs.casper.util._
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
    * @return normalizedFaultTolerance float between -1 and 1
    */
  def normalizedFaultTolerance(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Float]
}

object FinalityDetector {
  def apply[F[_]](implicit ev: FinalityDetector[F]): FinalityDetector[F] = ev

  case class Committee(validators: Set[Validator], quorum: Long)

  // Calculate threshold value as described in the specification.
  // Note that validator weights (`q` and `n`) are normalized to 1.
  private[casper] def calculateThreshold(q: Long, n: Long): Float = (2.0f * q - n) / (2 * n)
}

class FinalityDetectorInstancesImpl[F[_]: Monad: Log] extends FinalityDetector[F] {

  def normalizedFaultTolerance(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Float] =
    for {
      weights      <- ProtoUtil.mainParentWeightMap(dag, candidateBlockHash)
      committeeOpt <- findBestCommittee(dag, candidateBlockHash, weights)
    } yield committeeOpt
      .map(committee => FinalityDetector.calculateThreshold(committee.quorum, weights.values.sum))
      .getOrElse(0f)

  def levelZeroMsgs(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      validators: List[Validator]
  ): F[Map[Validator, List[BlockMetadata]]] = {

    // Get level zero messages of the specified validator
    def levelZeroMsgsOfValidator(
        validator: Validator
    ): F[List[BlockMetadata]] =
      dag.latestMessage(validator).flatMap {
        case Some(latestMsgByValidator) =>
          DagOperations
            .bfTraverseF[F, BlockMetadata](List(latestMsgByValidator))(
              previousAgreedBlockFromTheSameValidator(
                dag,
                _,
                candidateBlockHash,
                validator
              )
            )
            .toList
        case None => List.empty[BlockMetadata].pure[F]
      }

    /*
     * Traverses back the j-DAG of `block` (one step at a time), following `validator`'s blocks
     * and collecting them as long as they are descendants of the `candidateBlockHash`.
     */
    def previousAgreedBlockFromTheSameValidator(
        dag: DagRepresentation[F],
        block: BlockMetadata,
        candidateBlockHash: BlockHash,
        validator: Validator
    ): F[List[BlockMetadata]] = {
      // Assumes that validator always includes his last message as justification.
      val previousHashO = block.justifications
        .find(
          _.validatorPublicKey == validator
        )
        .map(_.latestBlockHash)

      previousHashO match {
        case Some(previousHash) =>
          ProtoUtil
            .isInMainChain[F](dag, candidateBlockHash, previousHash)
            .flatMap[List[BlockMetadata]](
              isActiveVote =>
                // If parent block of `block` is not in the main chain of `candidateBlockHash`
                // we don't include it in the set of level-0 messages.
                if (isActiveVote) dag.lookup(previousHash).map(_.toList)
                else List.empty[BlockMetadata].pure[F]
            )
        case None =>
          List.empty[BlockMetadata].pure[F]
      }
    }

    validators.foldLeftM(Map.empty[Validator, List[BlockMetadata]]) {
      case (acc, v) =>
        levelZeroMsgsOfValidator(v).map(acc.updated(v, _))
    }
  }

  // Reducing L to a committee with quorum q
  // In a loop, prune L to quorum q, and prune L to the set of validators with a block in L_k,
  // until both steps don't change L anymore.
  def pruningLoop(
      dag: DagRepresentation[F],
      committeeApproximation: Set[Validator],
      levelZeroMsgs: Map[Validator, List[BlockMetadata]],
      weightMap: Map[Validator, Long],
      q: Long,
      k: Int = 1
  ): F[Option[Committee]] =
    for {
      sweepResult <- sweep(
                      dag,
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
      committee <- if (prunedCommittee == committeeApproximation) {
                    if (prunedCommittee.isEmpty) {
                      none[Committee].pure[F]
                    } else {
                      val quorum = blockLevelTags.values.flatMap { blockScoreAccumulator =>
                        if (blockScoreAccumulator.blockLevel >= 1 && prunedCommittee.contains(
                              blockScoreAccumulator.block.validatorPublicKey
                            )) {
                          blockScoreAccumulator.estimateQ.some
                        } else {
                          None
                        }
                      }.min
                      Committee(prunedCommittee, quorum).some.pure[F]
                    }
                  } else {
                    pruningLoop(dag, prunedCommittee, levelZeroMsgs, weightMap, q, k)
                  }
    } yield committee

  def findBestQLoop(
      dag: DagRepresentation[F],
      committeeApproximation: Set[Validator],
      levelZeroMsgs: Map[Validator, List[BlockMetadata]],
      weightMap: Map[Validator, Long],
      q: Long,
      k: Int = 1,
      qBestCommittee: Option[Committee] = None
  ): F[Option[Committee]] =
    for {
      committeeOpt <- pruningLoop(
                       dag,
                       committeeApproximation,
                       levelZeroMsgs,
                       weightMap,
                       q,
                       k
                     )
      result <- committeeOpt match {
                 case None => qBestCommittee.pure[F]
                 case Some(committee) =>
                   findBestQLoop(
                     dag,
                     committee.validators,
                     levelZeroMsgs,
                     weightMap,
                     committee.quorum + 1,
                     k,
                     committee.some
                   )
               }
    } yield result

  // Tag level information for each block in one-pass
  def sweep(
      dag: DagRepresentation[F],
      committeeApproximation: Set[Validator],
      levelZeroMsgs: Map[Validator, List[BlockMetadata]],
      q: Long,
      k: Int,
      weightMap: Map[Validator, Long]
  ): F[(Map[BlockHash, BlockScoreAccumulator], Map[Validator, Int])] = {
    val lowestLevelZeroMsgs = committeeApproximation
      .flatMap(v => levelZeroMsgs(v).lastOption)
      .toList
    val stream = DagOperations.bfToposortTraverseF(lowestLevelZeroMsgs)(
      b =>
        for {
          bsOpt <- dag.justificationToBlocks(b.blockHash)
          filterBs <- bsOpt
                       .getOrElse(Set.empty)
                       .toList
                       .traverse(
                         dag.lookup
                       )
                       .map(_.flatten)
        } yield filterBs
    )

    val blockLevelTags =
      lowestLevelZeroMsgs.map(b => b.blockHash -> BlockScoreAccumulator.empty(b)).toMap

    val effectiveWeight: Validator => Long = (vid: Validator) =>
      if (committeeApproximation.contains(vid)) weightMap(vid) else 0L

    stream
      .foldLeftF((blockLevelTags, Map.empty[Validator, Int])) {
        case ((blockLevelTags, validatorLevel), b) =>
          val currentBlockScore = blockLevelTags(b.blockHash)
          val updatedBlockScore = BlockScoreAccumulator.updateOwnLevel(
            currentBlockScore,
            q,
            k,
            effectiveWeight
          )
          val updatedBlockLevelTags = blockLevelTags.updated(b.blockHash, updatedBlockScore)
          for {
            // after update current block's tag information,
            // we need update its children seen blocks's level information as well
            blockWithSpecifiedJustification <- dag.justificationToBlocks(b.blockHash)
            updatedBlockLevelTags <- blockWithSpecifiedJustification
                                      .getOrElse(Set.empty)
                                      .toList
                                      .foldLeftM(updatedBlockLevelTags) {
                                        case (acc, child) =>
                                          for {
                                            childBlock <- dag.lookup(child).map(_.get)
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
            vid = b.validatorPublicKey
            updatedValidatorLevel = if (committeeApproximation.contains(vid)) {
              val maxLevel = math.max(
                validatorLevel.getOrElse(vid, 0),
                updatedBlockScore.blockLevel
              )
              validatorLevel.updated(vid, maxLevel)
            } else
              validatorLevel
          } yield (updatedBlockLevelTags, updatedValidatorLevel)
      }
  }

  /*
   * Returns a list of validators whose latest messages are votes for `candidateBlockHash`.
   * i.e. checks whether latest blocks from these validators are in the main chain of `candidateBlockHash`.
   */
  private def getAgreeingValidators(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[List[Validator]] =
    weights.keys.toList.filterA { validator =>
      for {
        latestMessageHash <- dag
                              .latestMessageHash(
                                validator
                              )
        result <- latestMessageHash match {
                   case Some(b) =>
                     ProtoUtil.isInMainChain[F](
                       dag,
                       candidateBlockHash,
                       b
                     )
                   case _ => false.pure[F]
                 }
      } yield result
    }

  /*
   * Finds the best level-1 committee for a given candidate block
   */
  def findBestCommittee(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[Option[Committee]] =
    for {
      committeeApproximation <- getAgreeingValidators(dag, candidateBlockHash, weights)
      totalWeight            = weights.values.sum
      maxWeightApproximation = committeeApproximation.map(weights).sum
      // To have a committee of half the total weight,
      // you need at least twice the weight of the maxWeightApproximation to be greater than the total weight.
      // If that is false, we don't need to compute best committee
      // as we know the value is going to be below 0 and thus useless for finalization.
      result <- if (2 * maxWeightApproximation <= totalWeight) {
                 none[Committee].pure[F]
               } else {
                 for {
                   levelZeroMsgs <- levelZeroMsgs(
                                     dag,
                                     candidateBlockHash,
                                     committeeApproximation
                                   )
                   committeeMembersAfterPruning <- findBestQLoop(
                                                    dag,
                                                    committeeApproximation.toSet,
                                                    levelZeroMsgs,
                                                    weights,
                                                    maxWeightApproximation / 2
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
  @scala.annotation.tailrec
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
        Some((k, totalWeightOfSupporters))
      } else {
        calculateLevelAndQ(self, k - 1, q, effectiveWeight)
      }
    }
}
