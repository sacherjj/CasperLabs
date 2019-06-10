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

    def replaceBlockHashWithChildren(
        b: BlockHash
    ): F[List[BlockHash]] =
      for {
        c <- blockDag.children(b).map(_.getOrElse(Set.empty[BlockHash]))
      } yield if (c.nonEmpty) c.toList else List(b)

    def stillSame(
        children: List[BlockHash],
        nextChildren: List[BlockHash]
    ): Boolean =
      children.toSet == nextChildren.toSet

    /*
     * it will return latestMessages except those blocks whose descendant
     * exists in latestMessages.
     */
    def tipsOfLatestMessages(blocks: List[BlockHash]): F[List[BlockHash]] =
      for {
        cr       <- blocks.flatTraverse(replaceBlockHashWithChildren)
        children = cr.distinct
        result <- if (stillSame(blocks, children)) {
                   children.pure[F]
                 } else {
                   tipsOfLatestMessages(children)
                 }
      } yield result

    for {
      score            <- lmdScoring(blockDag, latestMessagesHashes)
      newMainParent    <- forkChoiceTip(blockDag, lastFinalizedBlockHash, score)
      parents          <- tipsOfLatestMessages(latestMessagesHashes.values.toList)
      secondaryParents = parents.filter(_ != newMainParent)
    } yield newMainParent +: secondaryParents
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
      case Some(mainChildren) =>
        for {
          // make sure they are reachable from latestMessages
          reachableMainChildren <- mainChildren.filterA(b => scores.contains(b).pure[F])
          result <- if (reachableMainChildren.isEmpty) {
                     blockHash.pure[F]
                   } else {
                     forkChoiceTip[F](
                       blockDag,
                       reachableMainChildren.maxBy(b => scores.getOrElse(b, 0L) -> b.toString()),
                       scores
                     )
                   }
        } yield result
    }

}
