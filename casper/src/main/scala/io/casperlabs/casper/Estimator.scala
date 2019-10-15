package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.equivocations.{EquivocationDetector, EquivocationsTracker}
import io.casperlabs.casper.util.DagOperations
import io.casperlabs.casper.util.ProtoUtil.weightFromValidatorByDag
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Weight
import io.casperlabs.storage.dag.DagRepresentation

import scala.collection.immutable.Map

object Estimator {
  type BlockHash = ByteString
  type Validator = ByteString

  import Weight._

  implicit val decreasingOrder = Ordering[Long].reverse

  /* Should not be used as long as `DagRepresentation` is not immutable. See NODE-923
  def tips[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      genesis: BlockHash,
      equivocationsTracker: EquivocationsTracker
  ): F[List[BlockHash]] =
    for {
      latestMessageHashes <- dag.latestMessageHashes
      result <- Estimator
                 .tips[F](dag, genesis, latestMessageHashes, equivocationsTracker)
    } yield result
   */

  def tips[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      genesis: BlockHash,
      latestMessageHashes: Map[Validator, BlockHash],
      equivocationsTracker: EquivocationsTracker
  ): F[List[BlockHash]] = {

    /** Eliminate any latest message which has a descendant which is a latest message
      * of another validator, because in that case those descendants should be the tips. */
    def tipsOfLatestMessages(
        latestMessages: List[BlockHash],
        stopHash: BlockHash
    ): F[List[BlockHash]] =
      if (latestMessages.isEmpty) List(genesis).pure[F]
      else {
        // Start from the highest latest messages and traverse backwards
        implicit val ord = DagOperations.blockTopoOrderingDesc
        for {
          latestMessagesMeta <- latestMessages.traverse(dag.lookup).map(_.flatten)
          tips <- DagOperations
                   .bfToposortTraverseF[F](latestMessagesMeta)(
                     _.parents.toList.traverse(dag.lookup(_)).map(_.flatten)
                   )
                   .takeUntil(_.messageHash == stopHash)
                   // We start with the tips and remove any message
                   // that is reachable through the parent-child link from other tips.
                   // This should leave us only with the tips that cannot be reached from others.
                   .foldLeft(latestMessagesMeta.map(_.messageHash).toSet) {
                     case (tips, message) =>
                       tips -- message.parents
                   }
        } yield tips.toList
      }

    for {
      lca <- if (latestMessageHashes.isEmpty) genesis.pure[F]
            else
              DagOperations.latestCommonAncestorsMainParent(dag, latestMessageHashes.values.toList)
      equivocatingValidators <- EquivocationDetector.detectVisibleFromJustifications(
                                 dag,
                                 latestMessageHashes,
                                 equivocationsTracker
                               )
      scores           <- lmdScoring(dag, lca, latestMessageHashes, equivocatingValidators)
      newMainParent    <- forkChoiceTip(dag, lca, scores)
      parents          <- tipsOfLatestMessages(latestMessageHashes.values.toList, lca)
      secondaryParents = parents.filter(_ != newMainParent)
      sortedSecParents = secondaryParents
        .sortBy(b => scores.getOrElse(b, Zero) -> b.toStringUtf8)
        .reverse
    } yield newMainParent +: sortedSecParents
  }

  /** Computes scores for LMD GHOST.
    *
    * Starts at the latest messages from currently bonded validators
    * and traverses up to the stop hash, collecting blocks' scores
    * (which is the weight of validators who include that block as main parent of their block).
    *
    * @param stopHash Block at which we stop computing scores. Should be latest common ancestor of `latestMessagesHashes`.
    * @return Scores map.
    */
  def lmdScoring[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      stopHash: BlockHash,
      latestMessageHashes: Map[Validator, BlockHash],
      equivocatingValidators: Set[Validator]
  ): F[Map[BlockHash, Weight]] =
    latestMessageHashes.toList.foldLeftM(Map.empty[BlockHash, Weight]) {
      case (acc, (validator, latestMessageHash)) =>
        DagOperations
          .bfTraverseF[F, BlockHash](List(latestMessageHash))(
            hash => dag.lookup(hash).map(_.get.parents.take(1).toList)
          )
          .takeUntil(_ == stopHash)
          .foldLeftF(acc) {
            case (acc2, blockHash) =>
              (if (equivocatingValidators.contains(validator)) {
                 Zero.pure[F]
               } else {
                 weightFromValidatorByDag(dag, blockHash, validator)
               }).map { realWeight =>
                val oldValue = acc2.getOrElse(blockHash, Zero)
                acc2.updated(blockHash, realWeight + oldValue)
              }
          }
    }

  /**
    * Computes fork choice.
    *
    * @param dag Representation of the Block DAG.
    * @param startingBlock Starting block for the fork choice rule.
    * @param scores Map of block's scores.
    * @return Block hash chosen by the fork choice rule.
    */
  def forkChoiceTip[F[_]: Monad](
      dag: DagRepresentation[F],
      startingBlock: BlockHash,
      scores: Map[BlockHash, Weight]
  ): F[BlockHash] =
    dag.getMainChildren(startingBlock).flatMap { mainChildren =>
      {
        // make sure they are reachable from latestMessages
        val reachableMainChildren = mainChildren.filter(scores.contains)
        if (reachableMainChildren.isEmpty) {
          startingBlock.pure[F]
        } else {
          val highestScoreChild =
            reachableMainChildren.maxBy(b => scores(b) -> b.toStringUtf8)
          forkChoiceTip[F](
            dag,
            highestScoreChild,
            scores
          )
        }
      }
    }

}
