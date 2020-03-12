package io.casperlabs.storage.dag

import cats.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.crypto.codec.Base16
import cats.data.NonEmptyList
import io.casperlabs.casper.consensus.Block
import io.casperlabs.metrics.Metered
import io.casperlabs.models.BlockImplicits._

trait AncestorsStorage[F[_]] { self: DagLookup[F] =>

  implicit val MT: MonadThrowable[F]

  /** Finds an ancestor of `startHash` at `height` in p-DAG.
    *
    * This is done by recursively looking up a parent of a block at specific height.
    *
    * Every block is stored with a list of its parents at heights that are power of 2.
    * i.e.
    * {{{
    * G <- A <- B <- C <- D <- E <- F <- G <- H
    * 0    1    2    3    4    5    6    7    8 (main rank, p-DAG ranks)
    * }}}
    * We're considering block `H`. Its parents' heights (counting from `H`) are:
    * {{{
    * g <- A <- B <- C <- D <- E <- F <- G <- H
    * 8    7    6    5    4    3    2    1
    * -                   -         -    - parents with power of 2 heights
    * 3                   2         1    0 exponents
    * }}}
    *
    * `H` will be stored with a list of parents and their distance from `H`:
    * (H, 1, G)
    * (H, 2, F)
    * (H, 4, D)
    * (H, 8, g)
    *
    * The same is done for all blocks that come before `H`.
    * If we're looking for a parent of `H` at height 1 we calculate a height difference,
    * which is 7, then decompose it into sum of powers of 2 (4, 2, 1) and recursively look
    * for ancestors at these heights. They will be at i-th index in the block's parents array.
    * {{{
    * (H, 4) -> (H, 2^2) -> D
    * (D, 2) -> (D, 2^1) -> B
    * (B, 1) -> (B, 2^0) -> A
    * }}}
    *
    * Note that if we were looking for an ancestor of the block that is at exactly power of 2 distance
    * from the block, we would find it immediately in the DB.
    */
  def getAncestorAt(start: Message, height: Long): F[Message] = {
    val diff = start.mainRank - height
    AncestorsStorage.decompose(diff).foldM(start) {
      case (b, h) =>
        findAncestor(b.messageHash, h).flatMap {
          case None =>
            // PrettyPrinter not available here.
            val hashStr = Base16.encode(b.messageHash.toByteArray)
            MT.raiseError[Message](
              new IllegalStateException(
                s"Missing expected ancestor of $hashStr at height $h"
              )
            )
          case Some(ancestorHash) =>
            self.lookupUnsafe(ancestorHash)
        }
    }
  }

  /**
    * Collects power of 2 ancestors of the block.
    */
  def collectMessageAncestors(block: Block): F[List[(Long, BlockHash)]] =
    if (block.isGenesisLike)
      List.empty[(Long, BlockHash)].pure[F]
    else {
      MT.tailRecM(NonEmptyList.one((1L, block.parents.head))) {
        case ancestors =>
          val (ancestorDistance, ancestorHash) = ancestors.head
          findAncestor(ancestorHash, ancestorDistance)
            .map {
              case None =>
                Right(ancestors.toList)
              case Some(nextAncestor) =>
                val visitedAncestors = (ancestorDistance * 2, nextAncestor) :: ancestors
                Left(visitedAncestors)
            }
      }
    }

  /**
    * Looks up an ancestor of the block at specified distance from it.
    *
    * @param block
    * @return Ancestor hash at the specified distance from the `block`.
    */
  private[storage] def findAncestor(block: BlockHash, distance: Long): F[Option[BlockHash]]
}

object AncestorsStorage {
  trait MeteredAncestorsStorage[F[_]] extends AncestorsStorage[F] with Metered[F] {
    self: DagLookup[F] =>
    abstract override private[storage] def findAncestor(block: BlockHash, distance: Long) =
      incAndMeasure("findAncestor", super.findAncestor(block, distance))
  }

  sealed trait Relation extends Product with Serializable
  object Relation {
    case object Ancestor   extends Relation
    case object Descendant extends Relation
    case object Equal      extends Relation

    def equal: Relation      = Equal
    def ancestor: Relation   = Ancestor
    def descendant: Relation = Descendant
  }

  /** Decomposes `n` as a sum of powers of 2, in decreasing order */
  private[dag] def decompose(n: Long): List[Long] = n match {
    case nonPositive if nonPositive <= 0 => Nil
    case 1                               => List(1L)
    case 2                               => List(2L)
    case 3                               => List(2L, 1L)
    case 4                               => List(4L)
    case m =>
      val x  = BigInt(m)
      val i0 = x.lowestSetBit
      val p0 = 1L << i0

      i0.until(x.bitLength)
        .foldLeft(List.empty[Long] -> p0) {
          case ((powers, p), i) =>
            if (x.testBit(i)) (p :: powers, 2 * p)
            else (powers, 2 * p)
        }
        ._1
  }
}
