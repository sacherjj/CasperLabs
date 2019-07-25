package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockDagRepresentation
import io.casperlabs.casper.util.DagOperations
import io.casperlabs.casper.util.ProtoUtil.weightFromValidatorByDag

import scala.collection.immutable.{Map, Set}

object Estimator {
  type BlockHash = ByteString
  type Validator = ByteString

  implicit val decreasingOrder = Ordering[Long].reverse

  def tips[F[_]: Monad](
      blockDag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[IndexedSeq[BlockHash]] =
    for {
      latestMessageHashes <- blockDag.latestMessageHashes
      result <- Estimator
                 .tips[F](blockDag, lastFinalizedBlockHash, latestMessageHashes)
    } yield result.toIndexedSeq

  def tips[F[_]: Monad](
      blockDag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash,
      latestMessagesHashes: Map[Validator, BlockHash]
  ): F[List[BlockHash]] = {

    /** Finds children of the block b that can have been scored by the LMD algorithm.
      * If no children exist (block B is the tip) return the tip.
      *
      * @param b block for which we want to find tips.
      * @param scores map of the scores from the block hash to a score
      * @return tips of the DAG.
      */
    def getBlockTips(
        b: BlockHash,
        scores: Map[BlockHash, Long]
    ): F[List[BlockHash]] =
      blockDag
        .children(b)
        .map(_.getOrElse(Set.empty).filter(scores.contains))
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
        children <- blocks.flatTraverse(getBlockTips(_, scores)).map(_.distinct)
        result <- if (blocks.toSet == children.toSet) {
                   children.pure[F]
                 } else {
                   tipsOfLatestMessages(children, scores)
                 }
      } yield result

    for {
      scores           <- lmdScoring(blockDag, latestMessagesHashes)
      newMainParent    <- forkChoiceTip(blockDag, lastFinalizedBlockHash, scores)
      parents          <- tipsOfLatestMessages(latestMessagesHashes.values.toList, scores)
      secondaryParents = parents.filter(_ != newMainParent)
      sortedSecParents = secondaryParents
        .sortBy(b => scores.getOrElse(b, 0L) -> b.toStringUtf8)
        .reverse
    } yield newMainParent +: sortedSecParents
  }

  /*
   * Compute the scores for LMD GHOST.
   * @return The scores map
   */
  def lmdScoring[F[_]: Monad](
      blockDag: BlockDagRepresentation[F],
      latestMessagesHashes: Map[Validator, BlockHash]
  ): F[Map[BlockHash, Long]] =
    latestMessagesHashes.toList.foldLeftM(Map.empty[BlockHash, Long]) {
      case (acc, (validator, latestMessageHash)) =>
        DagOperations
          .bfTraverseF[F, BlockHash](List(latestMessageHash))(
            hash => blockDag.lookup(hash).map(_.get.parents.take(1))
          )
          .foldLeftF(acc) {
            case (acc2, blockHash) =>
              for {
                weight   <- weightFromValidatorByDag(blockDag, blockHash, validator)
                oldValue = acc2.getOrElse(blockHash, 0L)
              } yield acc2.updated(blockHash, weight + oldValue)
          }
    }

  def forkChoiceTip[F[_]: Monad](
      blockDag: BlockDagRepresentation[F],
      blockHash: BlockHash,
      scores: Map[BlockHash, Long]
  ): F[BlockHash] =
    blockDag.getMainChildren(blockHash).flatMap {
      case None =>
        blockHash.pure[F]
      case Some(mainChildren) => {
        // make sure they are reachable from latestMessages
        val reachableMainChildren = mainChildren.filter(scores.contains)
        if (reachableMainChildren.isEmpty) {
          blockHash.pure[F]
        } else {
          val reachableChildrenSorted =
            reachableMainChildren.maxBy(b => scores.getOrElse(b, 0L) -> b.toStringUtf8)
          forkChoiceTip[F](
            blockDag,
            reachableChildrenSorted,
            scores
          )
        }
      }
    }

}
