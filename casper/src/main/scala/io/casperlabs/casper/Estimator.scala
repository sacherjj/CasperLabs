package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.DagRepresentation
import io.casperlabs.casper.util.DagOperations
import io.casperlabs.casper.util.ProtoUtil.weightFromValidatorByDag

import scala.collection.immutable.{Map, Set}

object Estimator {
  type BlockHash = ByteString
  type Validator = ByteString

  implicit val decreasingOrder = Ordering[Long].reverse

  def tips[F[_]: Monad](
      dag: DagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[IndexedSeq[BlockHash]] =
    for {
      latestMessageHashes <- dag.latestMessageHashes
      result <- Estimator
                 .tips[F](dag, lastFinalizedBlockHash, latestMessageHashes)
    } yield result.toIndexedSeq

  def tips[F[_]: Monad](
      dag: DagRepresentation[F],
      lastFinalizedBlockHash: BlockHash,
      latestMessagesHashes: Map[Validator, BlockHash]
  ): F[List[BlockHash]] = {

    /** Finds children of the block b that have been scored by the LMD algorithm.
      * If no children exist (block B is the tip) return the block.
      *
      * @param b block for which we want to find tips.
      * @param scores map of the scores from the block hash to a score
      * @return Children of the block.
      */
    def getChildrenOrSelf(
        b: BlockHash,
        scores: Map[BlockHash, Long]
    ): F[List[BlockHash]] =
      dag
        .children(b)
        .map(_.filter(scores.contains))
        .map(c => if (c.isEmpty) List(b) else c.toList)

    /*
     * Returns latestMessages except those blocks whose descendant
     * exists in latestMessages.
     */
    def tipsOfLatestMessages(
        blocks: List[BlockHash],
        scores: Map[BlockHash, Long]
    ): F[List[BlockHash]] =
      for {
        children <- blocks.flatTraverse(getChildrenOrSelf(_, scores)).map(_.distinct)
        result <- if (blocks.toSet == children.toSet) {
                   children.pure[F]
                 } else {
                   tipsOfLatestMessages(children, scores)
                 }
      } yield result

    for {
      scores           <- lmdScoring(dag, latestMessagesHashes)
      newMainParent    <- forkChoiceTip(dag, lastFinalizedBlockHash, scores)
      parents          <- tipsOfLatestMessages(latestMessagesHashes.values.toList, scores)
      secondaryParents = parents.filter(_ != newMainParent)
      sortedSecParents = secondaryParents
        .sortBy(b => scores.getOrElse(b, 0L) -> b.toStringUtf8)
        .reverse
    } yield newMainParent +: sortedSecParents
  }

  /** Computes scores for LMD GHOST.
    *
    * Starts at the latest messages from currently bonded validators
    * and traverses up to the Genesis, collecting scores per block.
    *
    * @return Scores map.
    */
  def lmdScoring[F[_]: Monad](
      dag: DagRepresentation[F],
      latestMessagesHashes: Map[Validator, BlockHash]
  ): F[Map[BlockHash, Long]] =
    latestMessagesHashes.toList.foldLeftM(Map.empty[BlockHash, Long]) {
      case (acc, (validator, latestMessageHash)) =>
        DagOperations
          .bfTraverseF[F, BlockHash](List(latestMessageHash))(
            hash => dag.lookup(hash).map(_.get.parents.take(1))
          )
          .foldLeftF(acc) {
            case (acc2, blockHash) =>
              weightFromValidatorByDag(dag, blockHash, validator).map(weight => {
                val oldValue = acc2.getOrElse(blockHash, 0L)
                acc2.updated(blockHash, weight + oldValue)
              })
          }
    }

  def forkChoiceTip[F[_]: Monad](
      dag: DagRepresentation[F],
      blockHash: BlockHash,
      scores: Map[BlockHash, Long]
  ): F[BlockHash] =
    dag.getMainChildren(blockHash).flatMap { mainChildren =>
      {
        // make sure they are reachable from latestMessages
        val reachableMainChildren = mainChildren.filter(scores.contains)
        if (reachableMainChildren.isEmpty) {
          blockHash.pure[F]
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
