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
        b: BlockHash,
        scores: Map[BlockHash, Long]
    ): F[List[BlockHash]] =
      for {
        c <- blockDag.children(b).map(_.getOrElse(Set.empty[BlockHash]).filter(scores.contains))
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
    def tipsOfLatestMessages(
        blocks: List[BlockHash],
        scores: Map[BlockHash, Long]
    ): F[List[BlockHash]] =
      for {
        cr       <- blocks.flatTraverse(replaceBlockHashWithChildren(_, scores))
        children = cr.distinct
        result <- if (stillSame(blocks, children)) {
                   children.pure[F]
                 } else {
                   tipsOfLatestMessages(children, scores)
                 }
      } yield result

    for {
      scores            <- lmdScoring(blockDag, latestMessagesHashes)
      newMainParent     <- forkChoiceTip(blockDag, lastFinalizedBlockHash, scores)
      parents           <- tipsOfLatestMessages(latestMessagesHashes.values.toList, scores)
      secondaryParents  = parents.filter(_ != newMainParent)
      secParentsDetails <- secondaryParents.traverse(blockDag.lookup)
      sortedSecParents = secParentsDetails.flatten
        .sortBy(b => -scores.getOrElse(b.blockHash, 0L) -> b.rank)
        .map(_.blockHash)
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
      case Some(mainChildren) =>
        for {
          // make sure they are reachable from latestMessages
          reachableMainChildren <- mainChildren.filterA(b => scores.contains(b).pure[F])
          result <- if (reachableMainChildren.isEmpty) {
                     blockHash.pure[F]
                   } else {
                     for {
                       blockMetadatas <- reachableMainChildren.traverse(blockDag.lookup)
                       mainParent = blockMetadatas.flatten
                         .maxBy(b => scores.getOrElse(b.blockHash, 0L) -> -b.rank)
                       tip <- forkChoiceTip[F](
                               blockDag,
                               mainParent.blockHash,
                               scores
                             )
                     } yield tip
                   }
        } yield result
    }

}
