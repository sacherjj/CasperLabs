package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.equivocations.{EquivocationDetector, EquivocationsTracker}
import io.casperlabs.casper.util.DagOperations
import io.casperlabs.casper.util.ProtoUtil.weightFromValidatorByDag
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.storage.dag.DagRepresentation

import scala.collection.immutable.Map

object Estimator {
  type BlockHash = ByteString
  type Validator = ByteString

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
    ): F[List[BlockHash]] = {
      implicit val ord = DagOperations.blockTopoOrderingDesc
      for {
        // Find the rank of the block at which the scoring algorithms stopped.
        minRank <- dag.lookup(stopHash).map(_.fold(0L)(_.rank))
        // Start from the higherst latest messages and traverse backwards;
        latestMessagesMeta <- latestMessages
                               .traverse(dag.lookup)
                               .map(_.flatten.sortBy(-_.rank))
        // any other latest message we visit is an ancestor that cannot be a tip.
        eliminated <- latestMessagesMeta.foldLeftM(Set.empty[BlockHash]) {
                       case (eliminated, latestMessageMeta)
                           if eliminated.contains(latestMessageMeta.messageHash) =>
                         // This tip has already been eliminated, no need to traverse through it again.
                         eliminated.pure[F]

                       case (eliminated, latestMessageMeta) =>
                         // Try going backwards from this message and eliminate what we haven't so far.
                         DagOperations
                           .bfToposortTraverseF[F](List(latestMessageMeta)) { blockMeta =>
                             // Follow all parents, except the ones we already eliminated,
                             // since their ancestors have already been traversed.
                             blockMeta.parents.toList
                               .filterNot(eliminated)
                               .traverse(dag.lookup)
                               .map(_.flatten)
                           }
                           .foldWhileLeft(eliminated) {
                             case (eliminated, meta)
                                 if meta.messageHash == latestMessageMeta.messageHash =>
                               // This is where we are staring from, so it can stay.
                               Left(eliminated)
                             case (eliminated, meta) if meta.rank >= minRank =>
                               // Anything we traverse through is not a tip.
                               Left(eliminated + meta.messageHash)
                             case (eliminated, _) =>
                               Right(eliminated)
                           }
                     }
      } yield latestMessages.filterNot(eliminated)
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
        .sortBy(b => scores.getOrElse(b, 0L) -> b.toStringUtf8)
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
  ): F[Map[BlockHash, Long]] =
    latestMessageHashes.toList.foldLeftM(Map.empty[BlockHash, Long]) {
      case (acc, (validator, latestMessageHash)) =>
        DagOperations
          .bfTraverseF[F, BlockHash](List(latestMessageHash))(
            hash => dag.lookup(hash).map(_.get.parents.take(1).toList)
          )
          .takeUntil(_ == stopHash)
          .foldLeftF(acc) {
            case (acc2, blockHash) =>
              (if (equivocatingValidators.contains(validator)) {
                 0L.pure[F]
               } else {
                 weightFromValidatorByDag(dag, blockHash, validator)
               }).map { realWeight =>
                val oldValue = acc2.getOrElse(blockHash, 0L)
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
      scores: Map[BlockHash, Long]
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
