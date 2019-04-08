package io.casperlabs.casper.util

import cats.{Eval, Monad}
import cats.implicits._
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.util.MapHelper.updatedWith
import io.casperlabs.catscontrib.ListContrib
import io.casperlabs.models.BlockMetadata
import io.casperlabs.shared.StreamT

import scala.annotation.tailrec
import scala.collection.immutable.{BitSet, HashSet, Queue}
import scala.collection.mutable

object DagOperations {

  def bfTraverseF[F[_]: Monad, A](start: List[A])(neighbours: A => F[List[A]]): StreamT[F, A] = {
    def build(q: Queue[A], prevVisited: HashSet[A]): F[StreamT[F, A]] =
      if (q.isEmpty) StreamT.empty[F, A].pure[F]
      else {
        val (curr, rest) = q.dequeue
        if (prevVisited(curr)) build(rest, prevVisited)
        else
          for {
            ns      <- neighbours(curr)
            visited = prevVisited + curr
            newQ    = rest.enqueue[A](ns.filterNot(visited))
          } yield StreamT.cons(curr, Eval.always(build(newQ, visited)))
      }

    StreamT.delay(Eval.now(build(Queue.empty[A].enqueue[A](start), HashSet.empty[A])))
  }

  /**
    * Determines the ancestors to a set of blocks which are not common to all
    * blocks in the set. Each starting block is assigned an index (hence the
    * usage of IndexedSeq) and this is used to refer to that block in the result.
    * A block B is an ancestor of a starting block with index i if the BitSet for
    * B contains i.
    * Example:
    * The DAG looks like:
    *         b6   b7
    *        |  \ / |
    *        b4  b5 |
    *          \ |  |
    *            b3 |
    *            |  |
    *           b1  b2
    *            |  /
    *          genesis
    *
    * Calling `uncommonAncestors(Vector(b6, b7), dag)` returns the following map:
    * Map(
    *   b6 -> BitSet(0),
    *   b4 -> BitSet(0),
    *   b7 -> BitSet(1),
    *   b2 -> BitSet(1)
    * )
    * This is because in the input the index of b6 is 0 and the index of b7 is 1.
    * Moreover, we can see from the DAG that b4 is an ancestor of b6, but not of b7,
    * while b2 is an ancestor of b7, but not of b6. b5 (and any of its ancestors) is
    * not included in the map because it is common to both b6 and b7.
    *
    * `uncommonAncestors(Vector(b2, b4, b5), dag)` returns the following map:
    * Map(
    *   b2 -> BitSet(0),
    *   b4 -> Bitset(1),
    *   b5 -> BitSet(2),
    *   b3 -> BitSet(1, 2),
    *   b1 -> BitSet(1, 2)
    * )
    * This is because in the input the index of b2 is 0, the index of b4 is 1 and
    * the index of b5 is 2. Blocks b1 and b3 are ancestors of b4 and b5, but not b2.
    * Genesis is not included because it is a common ancestor of all three input blocks.
    * @param blocks indexed sequence of blocks to determine uncommon ancestors of
    * @param dag the DAG
    * @param topoSort topological sort of the DAG, ensures ancestor computation is
    *                 done correctly
    * @return A map from uncommon ancestor blocks to BitSets, where a block B is
    *         and ancestor of starting block with index i if B's BitSet contains i.
    */
  def uncommonAncestors[F[_]: Monad](
      blocks: IndexedSeq[BlockMetadata],
      dag: BlockDagRepresentation[F]
  )(
      implicit topoSort: Ordering[BlockMetadata]
  ): F[Map[BlockMetadata, BitSet]] = {
    val commonSet = BitSet(0 until blocks.length: _*)
    def parents(b: BlockMetadata): F[List[BlockMetadata]] =
      b.parents.traverse(b => dag.lookup(b).map(_.get))
    def isCommon(set: BitSet): Boolean = set == commonSet

    // Initialize the algorithm with each starting block being an ancestor of only itself
    // (as indicated by each block be assiciated with a BitSet containing only its own index)
    // and each starting block in the priority queue. Note that the priority queue is
    // using the provided topological sort for the blocks, this guarantees we will be traversing
    // the DAG in a way which respects the causal (parent/child) ordering of blocks.
    val initMap = blocks.zipWithIndex.map { case (b, i) => b -> BitSet(i) }.toMap
    val q       = new mutable.PriorityQueue[BlockMetadata]()
    q.enqueue(blocks: _*)

    // Main loop for the algorithm. The loop terminates when
    // `uncommonEnqueued` is empty because it means there are no
    // more uncommon ancestors to encounter (all blocks further down the
    // DAG would be ancestors of all starting blocks). We cannot terminate simply
    // when the queue itself is empty because blocks that are common ancestors can
    // still exist in the queue.
    def loop(
        currMap: Map[BlockMetadata, BitSet],
        enqueued: HashSet[BlockMetadata],
        uncommonEnqueued: Set[BlockMetadata]
    ): F[Map[BlockMetadata, BitSet]] =
      if (uncommonEnqueued.isEmpty) currMap.pure[F]
      else {
        // Pull the next block from the queue
        val currBlock = q.dequeue()
        // Look up the ancestors of this block (recall that ancestry
        // is represented by having the index of that block present
        // in the bit set) Note: The call should never throw an exception
        // because we traverse in topological order (i.e. down parent links),
        // so either the block should be one of the starting ones or we will have
        // already encountered the block's parent.
        val currSet = currMap(currBlock)

        // Compute inputs for the next iteration of the loop
        val newInputs = for {
          // Look up the parents of the block
          currParents <- parents(currBlock)

          // Update the ancestry-map, set of enqueued block and set of
          // enqueued blocks which are not common to all starting blocks.
          (newMap, newEnqueued, newUncommon) = currParents.foldLeft(
            // Naturally, the starting point is the current map, and the
            // enqueued sets minus the block we just dequeued.
            (currMap, enqueued - currBlock, uncommonEnqueued - currBlock)
          ) {
            // for each parent, p, of the current block:
            case ((map, enq, unc), p) =>
              // if we have not enqueued it before, then enqueue it
              if (!enq(p)) q.enqueue(p)

              // the ancestry set for the parent is the union between its current
              // ancestry set and the one for the current block (because if the
              // current block is an ancestor of B then all ancestors of the current
              // block are also ancestors of B, i.e. ancestry is a transitive property)
              val pSet = map.getOrElse(p, BitSet.empty) | currSet

              // if the parent has been seen to be a common ancestor
              // then remove it from the uncommon set, otherwise ensure
              // it is included in the uncommon set
              val newUnc =
                if (isCommon(pSet)) unc - p
                else unc + p

              // Return the ancestry-map with entry for the parent updated,
              // ensure the parent is included in the enqueued set (because it
              // was either already there or we just enqueued it), and the
              // new set of uncommon ancestors
              (map.updated(p, pSet), enq + p, newUnc)
          }

          // The current block is taken out of the ancestry map if it is a
          // common ancestor because we are only interested in the uncommon ancestors.
          result = if (isCommon(currSet)) (newMap - currBlock, newEnqueued, newUncommon)
          else (newMap, newEnqueued, newUncommon)
        } yield result

        // Recursively call the function again (continuing the main loop), with the
        // updated inputs. This happens outside the for comprehension for stack safety.
        newInputs.flatMap {
          case (newMap, newEnqueued, newUncommon) => loop(newMap, newEnqueued, newUncommon)
        }
      }

    val startingSet = HashSet(blocks: _*)
    // Kick off the main loop with the initial map, noting
    // that all starting blocks are enqueued and all starting
    // blocks are presently uncommon (they are only known to
    // be ancestors of themselves), then filter all common ancestors
    // out of the final result
    loop(initMap, startingSet, startingSet).map(_.filter {
      case (_, set) => !isCommon(set)
    })
  }

  //Conceptually, the GCA is the first point at which the histories of b1 and b2 diverge.
  //Based on that, we compute by finding the first block from genesis for which there
  //exists a child of that block which is an ancestor of b1 or b2 but not both.
  def greatestCommonAncestorF[F[_]: Monad: BlockStore](
      b1: BlockMessage,
      b2: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[BlockMessage] =
    if (b1 == b2) {
      b1.pure[F]
    } else {
      def commonAncestorChild(
          b: BlockMessage,
          commonAncestors: Set[BlockMessage]
      ): F[List[BlockMessage]] =
        for {
          childrenHashesOpt      <- dag.children(b.blockHash)
          childrenHashes         = childrenHashesOpt.getOrElse(Set.empty[BlockHash])
          children               <- childrenHashes.toList.traverse(ProtoUtil.unsafeGetBlock[F])
          commonAncestorChildren = children.filter(commonAncestors)
        } yield commonAncestorChildren

      for {
        b1Ancestors     <- bfTraverseF[F, BlockMessage](List(b1))(ProtoUtil.unsafeGetParents[F]).toSet
        b2Ancestors     <- bfTraverseF[F, BlockMessage](List(b2))(ProtoUtil.unsafeGetParents[F]).toSet
        commonAncestors = b1Ancestors.intersect(b2Ancestors)
        gca <- bfTraverseF[F, BlockMessage](List(genesis))(commonAncestorChild(_, commonAncestors))
                .findF(
                  b =>
                    for {
                      childrenOpt <- dag.children(b.blockHash)
                      children    = childrenOpt.getOrElse(Set.empty[BlockHash]).toList
                      result <- children.existsM(
                                 hash =>
                                   for {
                                     c <- ProtoUtil.unsafeGetBlock[F](hash)
                                   } yield b1Ancestors(c) ^ b2Ancestors(c)
                               )
                    } yield result
                )
      } yield gca.get
    }
}
