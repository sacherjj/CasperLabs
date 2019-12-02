package io.casperlabs.casper.util

import cats.implicits._
import cats.data.NonEmptyList
import cats.{Eq, Eval, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.util.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.shared.{Log, StreamT}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import simulacrum.typeclass

import scala.collection.immutable.{BitSet, HashSet, Queue}
import scala.collection.mutable

object DagOperations {

  /** Some traversals take so long that tracking the visited nodes can fill the memory.
    * Key should reduce the data we are traversing to a small identifier.
    */
  @typeclass
  trait Key[A] {
    type K
    def key(a: A): K
  }
  object Key {
    def instance[A, B](k: A => B) = new Key[A] {
      type K = B
      def key(a: A) = k(a)
    }
    def identity[A]                = instance[A, A](a => a)
    implicit val blockKey          = instance[Block, BlockHash](_.blockHash)
    implicit val blockSummaryKey   = instance[BlockSummary, BlockHash](_.blockHash)
    implicit val messageSummaryKey = instance[Message, BlockHash](_.messageHash)
    implicit val blockHashKey      = identity[BlockHash]
  }

  /** Starts from the list of messages and follows their justifications,
    * sorting them by their rank value.
    *
    * Returns a stream.
    */
  def toposortJDagDesc[F[_]: Monad](
      dag: DagRepresentation[F],
      msgs: List[Message]
  ): StreamT[F, Message] = {
    implicit val blockTopoOrdering: Ordering[Message] = DagOperations.blockTopoOrderingDesc
    DagOperations.bfToposortTraverseF(
      msgs
    )(
      _.justifications.toList
        .traverse(j => dag.lookup(j.latestBlockHash))
        .map(_.flatten)
    )
  }

  /** Traverses j-past-cone of the block and returns messages by specified validator.
    */
  def swimlaneV[F[_]: Monad](
      validator: ByteString,
      message: Message,
      dag: DagRepresentation[F]
  ): StreamT[F, Message] = {
    // Messages visible in the direct justifications of the block.
    val messagePanorama =
      message.justifications.toList.traverse(j => dag.lookup(j.latestBlockHash)).map(_.flatten)
    val tail = StreamT.lift(messagePanorama).flatMap { jTips =>
      toposortJDagDesc[F](dag, jTips).filter(_.validatorId == validator)
    }
    if (message.validatorId == validator) {
      StreamT.pure[F, Message](message) ++ tail
    } else tail
  }

  def bfTraverseF[F[_]: Monad, A](
      start: List[A]
  )(neighbours: A => F[List[A]])(
      implicit k: Key[A]
  ): StreamT[F, A] = {
    def build(q: Queue[A], prevVisited: HashSet[k.K]): F[StreamT[F, A]] =
      if (q.isEmpty) StreamT.empty[F, A].pure[F]
      else {
        val (curr, rest) = q.dequeue
        if (prevVisited(k.key(curr))) build(rest, prevVisited)
        else
          for {
            ns      <- neighbours(curr)
            visited = prevVisited + k.key(curr)
            newQ    = rest.enqueue[A](ns.filterNot(n => visited(k.key(n))))
          } yield StreamT.cons(curr, Eval.always(build(newQ, visited)))
      }

    StreamT.delay(Eval.now(build(Queue.empty[A].enqueue[A](start), HashSet.empty[k.K])))
  }

  val longByteStringOrdering: Ordering[(Long, ByteString)] =
    Ordering.fromLessThan[(Long, ByteString)] {
      case (a, b) =>
        if (a._1 < b._1) true
        else if (b._1 < a._1) false
        else {
          val aStr = a._2.toStringUtf8
          val bStr = b._2.toStringUtf8
          if (aStr < bStr) true
          else if (bStr < aStr) false
          else true
        }
    }

  val bigIntByteStringOrdering: Ordering[(BigInt, ByteString)] =
    Ordering.fromLessThan[(BigInt, ByteString)] {
      case (a, b) =>
        if (a._1 < b._1) true
        else if (b._1 < a._1) false
        else {
          val aStr = a._2.toStringUtf8
          val bStr = b._2.toStringUtf8
          if (aStr < bStr) true
          else if (bStr < aStr) false
          else true
        }
    }

  val blockTopoOrderingAsc: Ordering[Message] =
    Ordering
      .by[Message, (Long, ByteString)](m => (m.rank, m.messageHash))(longByteStringOrdering)
      .reverse

  val blockTopoOrderingDesc: Ordering[Message] =
    Ordering.by[Message, (Long, ByteString)](m => (m.rank, m.messageHash))(longByteStringOrdering)

  def bfToposortTraverseF[F[_]: Monad](
      start: List[Message]
  )(
      neighbours: Message => F[List[Message]]
  )(implicit ord: Ordering[Message]): StreamT[F, Message] = {
    def build(
        q: mutable.PriorityQueue[Message],
        prevVisited: HashSet[BlockHash]
    ): F[StreamT[F, Message]] =
      if (q.isEmpty) StreamT.empty[F, Message].pure[F]
      else {
        val curr = q.dequeue
        if (prevVisited(curr.messageHash)) build(q, prevVisited)
        else
          for {
            ns      <- neighbours(curr)
            visited = prevVisited + curr.messageHash
            newQ    = q ++ ns.filterNot(b => visited(b.messageHash))
          } yield StreamT.cons(curr, Eval.always(build(newQ, visited)))
      }

    StreamT.delay(
      Eval.now(
        build(mutable.PriorityQueue.empty[Message] ++ start, HashSet.empty[BlockHash])
      )
    )
  }

  /**
    * Determines the ancestors to a set of blocks which are not common to all
    * blocks in the set. Each starting block is assigned an index (hence the
    * usage of IndexedSeq) and this is used to refer to that block in the result.
    * A block B is an ancestor of a starting block with index i if the BitSet for
    * B contains i.
    * Example:
    * The DAG looks like:
    * b6   b7
    * |  \ / |
    * b4  b5 |
    * \ |  |
    * b3 |
    * |  |
    * b1  b2
    * |  /
    * genesis
    *
    * Calling `uncommonAncestors(Vector(b6, b7), dag)` returns the following map:
    * Map(
    * b6 -> BitSet(0),
    * b4 -> BitSet(0),
    * b7 -> BitSet(1),
    * b2 -> BitSet(1)
    * )
    * This is because in the input the index of b6 is 0 and the index of b7 is 1.
    * Moreover, we can see from the DAG that b4 is an ancestor of b6, but not of b7,
    * while b2 is an ancestor of b7, but not of b6. b5 (and any of its ancestors) is
    * not included in the map because it is common to both b6 and b7.
    *
    * `uncommonAncestors(Vector(b2, b4, b5), dag)` returns the following map:
    * Map(
    * b2 -> BitSet(0),
    * b4 -> Bitset(1),
    * b5 -> BitSet(2),
    * b3 -> BitSet(1, 2),
    * b1 -> BitSet(1, 2)
    * )
    * This is because in the input the index of b2 is 0, the index of b4 is 1 and
    * the index of b5 is 2. Blocks b1 and b3 are ancestors of b4 and b5, but not b2.
    * Genesis is not included because it is a common ancestor of all three input blocks.
    *
    * @param blocks   indexed sequence of blocks to determine uncommon ancestors of
    * @param dag      the DAG
    * @param topoSort topological sort of the DAG, ensures ancestor computation is
    *                 done correctly
    * @return A map from uncommon ancestor blocks to BitSets, where a block B is
    *         and ancestor of starting block with index i if B's BitSet contains i.
    */
  def abstractUncommonAncestors[F[_]: Monad, A: Ordering](
      start: IndexedSeq[A],
      parents: A => F[List[A]]
  ): F[Map[A, BitSet]] = {
    val commonSet = BitSet(start.indices: _*)

    def isCommon(set: BitSet): Boolean = set == commonSet

    // Initialize the algorithm with each starting block being an ancestor of only itself
    // (as indicated by each block be assiciated with a BitSet containing only its own index)
    // and each starting block in the priority queue. Note that the priority queue is
    // using the provided topological sort for the blocks, this guarantees we will be traversing
    // the DAG in a way which respects the causal (parent/child) ordering of blocks.
    val initMap = start.zipWithIndex.map { case (b, i) => b -> BitSet(i) }.toMap
    val q       = new mutable.PriorityQueue[A]()
    q.enqueue(start: _*)

    // Main loop for the algorithm. The loop terminates when
    // `uncommonEnqueued` is empty because it means there are no
    // more uncommon ancestors to encounter (all blocks further down the
    // DAG would be ancestors of all starting blocks). We cannot terminate simply
    // when the queue itself is empty because blocks that are common ancestors can
    // still exist in the queue.
    def loop(
        currMap: Map[A, BitSet],
        enqueued: HashSet[A],
        uncommonEnqueued: Set[A]
    ): F[Map[A, BitSet]] =
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

    val startingSet = HashSet(start: _*)
    // Kick off the main loop with the initial map, noting
    // that all starting blocks are enqueued and all starting
    // blocks are presently uncommon (they are only known to
    // be ancestors of themselves), then filter all common ancestors
    // out of the final result
    loop(initMap, startingSet, startingSet).map(_.filter {
      case (_, set) => !isCommon(set)
    })
  }

  def uncommonAncestors[F[_]: Monad](
      blocks: IndexedSeq[Message],
      dag: DagRepresentation[F]
  )(
      implicit topoSort: Ordering[Message]
  ): F[Map[Message, BitSet]] = {
    def parents(b: Message): F[List[Message]] =
      b.parents.toList.traverse(b => dag.lookup(b).map(_.get))

    abstractUncommonAncestors[F, Message](blocks, parents)
  }

  //Conceptually, the GCA is the first point at which the histories of b1 and b2 diverge.
  //Based on that, we compute by finding the first block from genesis for which there
  //exists a child of that block which is an ancestor of b1 or b2 but not both.
  @deprecated("Use uncommonAncestors", "0.1")
  def greatestCommonAncestorF[F[_]: MonadThrowable: BlockStorage](
      b1: Block,
      b2: Block,
      genesis: Block,
      dag: DagRepresentation[F]
  ): F[Block] =
    if (b1 == b2) {
      b1.pure[F]
    } else {
      def commonAncestorChild(
          b: Block,
          commonAncestors: Set[Block]
      ): F[List[Block]] =
        for {
          childrenHashes         <- dag.children(b.blockHash)
          children               <- childrenHashes.toList.traverse(ProtoUtil.unsafeGetBlock[F])
          commonAncestorChildren = children.filter(commonAncestors)
        } yield commonAncestorChildren

      for {
        b1Ancestors     <- bfTraverseF[F, Block](List(b1))(ProtoUtil.unsafeGetParents[F]).toSet
        b2Ancestors     <- bfTraverseF[F, Block](List(b2))(ProtoUtil.unsafeGetParents[F]).toSet
        commonAncestors = b1Ancestors.intersect(b2Ancestors)
        gca <- bfTraverseF[F, Block](List(genesis))(commonAncestorChild(_, commonAncestors)).findF(
                b =>
                  for {
                    children <- dag.children(b.blockHash)
                    result <- children.toList.existsM(
                               hash =>
                                 for {
                                   c <- ProtoUtil.unsafeGetBlock[F](hash)
                                 } yield b1Ancestors(c) ^ b2Ancestors(c)
                             )
                  } yield result
              )
      } yield gca.get
    }

  /** Computes Latest Common Ancestor of two elements.
    */
  def latestCommonAncestorF[F[_]: MonadThrowable, A: Eq: Ordering](
      a: A,
      b: A
  )(next: A => F[A]): F[A] =
    if (Eq[A].eqv(a, b)) {
      a.pure[F]
    } else {
      Ordering[A].compare(a, b) match {
        case x if x < 0 =>
          // Block `b` is "higher" in the chain
          next(b).flatMap(latestCommonAncestorF(a, _)(next))
        case 0 =>
          // Both blocks have the same rank but they're different blocks.
          for {
            aa  <- next(a)
            bb  <- next(b)
            lca <- latestCommonAncestorF(aa, bb)(next)
          } yield lca
        case x if x > 0 =>
          next(a).flatMap(latestCommonAncestorF(b, _)(next))
      }
    }

  /** Computes Latest Common Ancestor of the set of elements.
    */
  def latestCommonAncestorF[F[_]: MonadThrowable, A: Eq: Ordering](
      starters: NonEmptyList[A]
  )(next: A => F[A]): F[A] =
    starters.foldLeftM(starters.head)(latestCommonAncestorF(_, _)(next))

  /** Computes Latest Common Ancestor of a set of blocks by following main-parent
    * vertices.
    *
    * @param dag Representation of a DAG.
    * @param starters Starting blocks.
    * @tparam F Effect type.
    * @return Latest Common Ancestor of starting blocks.
    */
  def latestCommonAncestorsMainParent[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      starters: NonEmptyList[BlockHash]
  ): F[Message] = {
    implicit val blocksOrdering = DagOperations.blockTopoOrderingDesc
    import io.casperlabs.casper.util.implicits.eqMessageSummary

    def lookup(hash: BlockHash): F[Message] =
      dag
        .lookup(hash)
        .flatMap(
          MonadThrowable[F].fromOption(
            _,
            new IllegalStateException(s"Missing dependency: ${PrettyPrinter.buildString(hash)}")
          )
        )

    starters
      .traverse(lookup)
      .flatMap {
        latestCommonAncestorF[F, Message](_) { block =>
          // Genesis doesn't have parents, so just return itself until it's recognised as LCA.
          block.parents.headOption.fold(block.pure[F])(lookup)
        }
      }
  }

  /** Check if there's a (possibly empty) path leading from any of the starting points to any of the targets. */
  def anyPathExists[F[_]: Monad, A](
      start: Set[A],
      targets: Set[A]
  )(neighbours: A => F[List[A]])(
      implicit k: Key[A]
  ): F[Boolean] =
    if (targets.isEmpty || start.isEmpty) false.pure[F]
    else
      bfTraverseF[F, A](start.toList)(neighbours).find(targets).map(_.nonEmpty)

  /** Check if a path in the p-DAG exists from ancestors to descendants (or self). */
  def anyDescendantPathExists[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      ancestors: Set[BlockHash],
      descendants: Set[BlockHash]
  ): F[Boolean] =
    anyPathExists(ancestors, descendants) { blockHash =>
      dag.children(blockHash).map(_.toList)
    }

  /** Collect all block hashes from the ancestor candidates through which can reach any of the descendants. */
  def collectWhereDescendantPathExists[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      ancestors: Set[BlockHash],
      descendants: Set[BlockHash]
  ): F[Set[BlockHash]] =
    if (ancestors.isEmpty || descendants.isEmpty) Set.empty[BlockHash].pure[F]
    else {
      // Traverse backwards rank by rank until we either visit all ancestors or go beyond the oldest.
      implicit val ord = blockTopoOrderingDesc
      for {
        ancestorMeta   <- ancestors.toList.traverse(dag.lookup).map(_.flatten)
        descendantMeta <- descendants.toList.traverse(dag.lookup).map(_.flatten)
        minRank        = if (ancestorMeta.isEmpty) 0 else ancestorMeta.map(_.rank).min
        reachable <- bfToposortTraverseF[F](descendantMeta) { blockMeta =>
                      blockMeta.parents.toList.traverse(dag.lookup).map(_.flatten)
                    }.foldWhileLeft(Set.empty[BlockHash]) {
                      case (reachable, msgSummary) if ancestors(msgSummary.messageHash) =>
                        Left(reachable + msgSummary.messageHash)
                      case (reachable, blockMeta) if blockMeta.rank >= minRank =>
                        Left(reachable)
                      case (reachable, _) =>
                        Right(reachable)
                    }
      } yield reachable
    }
}
