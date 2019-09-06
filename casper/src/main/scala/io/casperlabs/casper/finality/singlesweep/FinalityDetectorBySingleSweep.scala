package io.casperlabs.casper.finality.singlesweep

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.finality.FinalityDetectorUtil
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.shared.Log

/*
 * Implementation inspired by The Inspector algorithm
 *
 * https://hackingresear.ch/cbc-inspector/
 */
class FinalityDetectorBySingleSweepImpl[F[_]: Monad: Log] extends FinalityDetector[F] {

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
  ): F[Map[Validator, List[BlockMetadata]]] =
    validators.foldLeftM(Map.empty[Validator, List[BlockMetadata]]) {
      case (acc, v) =>
        FinalityDetectorUtil
          .levelZeroMsgsOfValidator(dag, v, candidateBlockHash)
          .map(acc.updated(v, _))
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

    implicit val blockTopoOrdering: Ordering[BlockMetadata] =
      DagOperations.blockTopoOrderingAsc

    val stream = DagOperations.bfToposortTraverseF(lowestLevelZeroMsgs)(
      b =>
        for {
          bsOpt <- dag.justificationToBlocks(b.blockHash)
          filterBs <- bsOpt.toList
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
            // After updating current block's tag information,
            // update its children seen blocks's level information as well
            blockWithSpecifiedJustification <- dag.justificationToBlocks(b.blockHash)
            updatedBlockLevelTags <- blockWithSpecifiedJustification.toList
                                      .foldLeftM(updatedBlockLevelTags) {
                                        case (acc, child) =>
                                          for {
                                            childBlock <- dag.lookup(child).map(_.get)
                                            childBlockStorage = acc
                                              .getOrElse(
                                                child,
                                                BlockScoreAccumulator.empty(childBlock)
                                              )
                                            updatedChildBlockStorage = BlockScoreAccumulator
                                              .inheritFromParent(
                                                childBlockStorage,
                                                updatedBlockScore
                                              )
                                          } yield acc.updated(child, updatedChildBlockStorage)
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
   * Finds the best level-1 committee for a given candidate block
   */
  def findBestCommittee(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[Option[Committee]] =
    for {
      committeeApproximationOpt <- committeeApproximation(
                                    dag,
                                    candidateBlockHash,
                                    weights
                                  )
      result <- committeeApproximationOpt match {
                 case Some((committeeApproximation, maxWeightApproximation)) =>
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
                                                      maxWeightApproximation
                                                    )
                   } yield committeeMembersAfterPruning
                 case None =>
                   none[Committee].pure[F]
               }
    } yield result

  /**
    * Finding validators who voting on the `candidateBlockHash`,
    * if twice the sum of weight of them are bigger than the
    * total weight, then return these validators and their sum of weight.
    * @param dag blockDag
    * @param candidateBlockHash blockHash of block to be estimate whether finalized
    * @param weights weight map
    * @return
    */
  private def committeeApproximation(
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[Option[(List[Validator], Long)]] =
    for {
      committee              <- getAgreeingValidators(dag, candidateBlockHash, weights)
      totalWeight            = weights.values.sum
      maxWeightApproximation = committee.map(weights).sum
      // To have a committee of half the total weight,
      // you need at least twice the weight of the maxWeightApproximation to be greater than the total weight.
      // If that is false, we don't need to compute best committee
      // as fault tolerance t = `(2q/w - 1)(1 - 2^-k)`, so t is going below 0 and thus useless for finalization.
      result = if (2 * maxWeightApproximation > totalWeight) {
        Some((committee, maxWeightApproximation))
      } else {
        None
      }
    } yield result

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

  // Children will inherit seen blocks from the parent
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
  // this algorithm can calculate level k in one pass.
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
