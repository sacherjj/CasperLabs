package io.casperlabs.comm.gossiping.synchronization

import cats.Monad
import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.kernel.Monoid
import com.google.protobuf.ByteString
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.shared.SemaphoreMap
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError._
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import scala.util.control.NonFatal

class SynchronizerImpl[F[_]: Concurrent: Log: Metrics](
    connectToGossip: Node => F[GossipService[F]],
    backend: SynchronizerImpl.Backend[F],
    maxPossibleDepth: Int,
    minBlockCountToCheckWidth: Int,
    maxBondingRate: Double,
    maxDepthAncestorsRequest: Int,
    // Only allow 1 sync per node at a time to not traverse the same thing twice.
    sourceSemaphoreMap: SemaphoreMap[F, Node],
    // Keep the synced DAG in memory so we can avoid traversing them repeatedly.
    syncedSummariesRef: Ref[F, Map[ByteString, SynchronizerImpl.SyncedSummary]]
) extends Synchronizer[F] {
  type Effect[A] = EitherT[F, SyncError, A]

  import io.casperlabs.comm.gossiping.synchronization.SynchronizerImpl._

  // This is supposed to be called every time a scheduled download is finished,
  // even when the resolution is that we already had it, so there should be no leaks.
  override def downloaded(blockHash: ByteString) =
    syncedSummariesRef.update(_ - blockHash)

  override def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Either[SyncError, Vector[BlockSummary]]] = {
    val effect = for {
      _              <- Metrics[F].incrementCounter("syncs")
      _              <- Metrics[F].incrementCounter("sync_targets", delta = targetBlockHashes.size.toLong)
      service        <- connectToGossip(source)
      justifications <- backend.justifications
      syncStateOrError <- loop(
                           source,
                           service,
                           targetBlockHashes.toList,
                           justifications,
                           SyncState.initial(targetBlockHashes)
                         )
      res <- syncStateOrError.fold(
              _.asLeft[Vector[BlockSummary]].pure[F],
              finalizeResult(source, _)
            )
      _ <- Metrics[F].incrementCounter(if (res.isLeft) "syncs_failed" else "syncs_succeeded")
    } yield res

    Metrics[F].gauge("syncs_ongoing") {
      sourceSemaphoreMap.withPermit(source) {
        effect.onError {
          case NonFatal(ex) =>
            Log[F].error(s"Failed to sync a DAG, source: ${source.show -> "peer"}: $ex") *>
              Metrics[F].incrementCounter("syncs_failed")
        }
      }
    }
  }

  private def loop(
      source: Node,
      service: GossipService[F],
      targetBlockHashes: List[ByteString],
      knownBlockHashes: List[ByteString],
      prevSyncState: SyncState
  ): F[Either[SyncError, SyncState]] =
    if (targetBlockHashes.isEmpty) {
      prevSyncState.asRight[SyncError].pure[F]
    } else {
      traverse(
        service,
        targetBlockHashes,
        knownBlockHashes,
        prevSyncState
      ).flatMap {
        case left @ Left(_) => (left: Either[SyncError, SyncState]).pure[F]
        case Right(newSyncState) =>
          missingDependencies(source, newSyncState.parentToChildren)
            .flatMap(
              missing =>
                if (prevSyncState.summaries == newSyncState.summaries)
                  // Looks like we received nothing new.
                  prevSyncState.asRight[SyncError].pure[F]
                else
                  // We have new roots for the DAG, we can ask the source to expand.
                  loop(
                    source,
                    service,
                    missing,
                    knownBlockHashes,
                    newSyncState
                  )
            )
      }
    }

  private def finalizeResult(
      source: Node,
      syncState: SyncState
  ): F[Either[SyncError, Vector[BlockSummary]]] =
    // Check that the stream didn't die before connecting to our DAG.
    missingDependencies(source, syncState.parentToChildren) flatMap { missing =>
      if (missing.isEmpty) {
        for {
          _         <- addSynced(source, syncState)
          summaries = topologicalSort(syncState)
        } yield summaries.asRight[SyncError]
      } else {
        MissingDependencies(missing.toSet).asLeft[Vector[BlockSummary]].leftWiden[SyncError].pure[F]
      }
    }

  /** Find the roots of a partial DAG that we don't have in our backend. */
  private def missingDependencies(
      source: Node,
      parentToChildren: Map[ByteString, Set[ByteString]]
  ): F[List[ByteString]] =
    greatestParents(parentToChildren).toList.filterA { blockHash =>
      for {
        syncedSummaries <- syncedSummariesRef.get
        // TODO: We could say that if we have it from *any* source, but not this,
        // then we add the new source to all dependencies and don't loop any more.
        // This could cut down traversing from sources we have not synced with before.
        isSynced  = syncedSummaries.get(blockHash).fold(false)(_.sources(source))
        isMissing <- if (isSynced) false.pure[F] else backend.notInDag(blockHash)
      } yield isMissing
    }

  /** Find parents which have no children, which form the leftmost edge of the DAG.
    * We should have these locally, otherwise we have to traverse further back.
    */
  private def greatestParents(
      parentToChildren: Map[ByteString, Set[ByteString]]
  ): Set[ByteString] = {
    val allParents = parentToChildren.keySet
    val allChildren = parentToChildren.values.foldLeft(Set.empty[ByteString]) {
      case (a, b) => a union b
    }
    allParents.diff(allChildren)
  }

  private def topologicalSort(syncState: SyncState): Vector[BlockSummary] =
    syncState.summaries.values.toVector.sortBy(_.rank)

  /** Remember that we got these summaries from this source,
    * so next time we don't have to travel that far back in their DAG. */
  private def addSynced(source: Node, syncState: SyncState): F[Unit] =
    syncedSummariesRef.update { syncedSummaries =>
      syncState.summaries.values.foldLeft(syncedSummaries) {
        case (syncedSummaries, summary) =>
          val ss = syncedSummaries.getOrElse(summary.blockHash, SyncedSummary(summary))
          syncedSummaries + (summary.blockHash -> ss.addSource(source))
      }
    }

  /** Ask the source to traverse back from whatever our last unknown blocks were. */
  private def traverse(
      service: GossipService[F],
      targetBlockHashes: List[ByteString],
      knownBlockHashes: List[ByteString],
      prevSyncState: SyncState
  ): F[Either[SyncError, SyncState]] = {
    val currentTargets = targetBlockHashes.toSet
    service
      .streamAncestorBlockSummaries(
        StreamAncestorBlockSummariesRequest(
          targetBlockHashes = targetBlockHashes,
          knownBlockHashes = knownBlockHashes,
          maxDepth = maxDepthAncestorsRequest
        )
      )
      .foldWhileLeftEvalL(prevSyncState.asRight[SyncError].pure[F]) {
        case (Right(syncState), summary) =>
          val effect = for {
            _ <- EitherT.liftF(
                  Metrics[F].incrementCounter("summaries_traversed")
                )
            _ <- validate(summary)
            _ <- noCycles(syncState, summary)
            (iterDist, origDist) <- reachable(
                                     syncState,
                                     summary,
                                     currentTargets
                                   )
            newSyncState = syncState.append(summary, iterDist, origDist)
            _            <- notTooWide(newSyncState)
          } yield newSyncState

          // If it's an error, stop the fold, otherwise carry on.
          effect.value.map {
            case x @ Left(_) =>
              (x: Either[SyncError, SyncState])
                .asRight[Either[SyncError, SyncState]]
            case x @ Right(_) =>
              (x: Either[SyncError, SyncState])
                .asLeft[Either[SyncError, SyncState]]
          }
        // Never happens
        case _ => Sync[F].raiseError(new RuntimeException)
      } map {
      _.map(_.endIteration)
    }
  }

  /** Formal validation of the block summary fields. It should weed out complete rubbish,
    * which should make certain forgeries like dependency cycles impossible.
    */
  private def validate(summary: BlockSummary): EitherT[F, SyncError, Unit] =
    EitherT(
      backend
        .validate(summary)
        .as(().asRight[SyncError])
        .handleError(e => (ValidationError(summary, e): SyncError).asLeft[Unit])
    )

  /** Check that we are not on a loop, that we we haven't seen the same summary in this
    * traversal iteration. We might have seen it already because of the BFS nature and
    * repeated traversals, but that will be caught by it not being an ancestor of the
    * current targets, which will go backwards in the DAG all the time.
    *
    * Forging summaries where a hash appears as an ancestor should not be possible,
    * because you'd have to put the hash of the descendant into the justifications
    * of the ancestor, but doing so changes the hash of the ancestor, and thus
    * also changes the hash of the descendant. As long as we check the correctness
    * of the hash first, all we'd have to detect is if the stream itself is looping.
    */
  private def noCycles(
      syncState: SyncState,
      summary: BlockSummary
  ): EitherT[F, SyncError, Unit] =
    if (syncState.iterationState.visited(summary.blockHash)) {
      EitherT((Cycle(summary): SyncError).asLeft[Unit].pure[F])
    } else {
      EitherT(().asRight[SyncError].pure[F])
    }

  /** Checks that at the current depth we are observing we haven't received so many blocks that it would indicate
    * an abnormally wide, artificially generated graph. We use a simple formula to estimate the upper bound of how
    * many blocks we can see. We assume that there is a limit on how quickly validators and bond and unbond, and
    * that this limit can be expressed as a "bonding rate" which represents the rate of change per DAG rank.
    * For example a rate of 0.1 would mean there can be a bonding event every 10th rank. Bonding has to happen
    * in a way that that block is the only block at that rank, since it cannot be merged with any other block,
    * otherwise the set of bonded validators among parents would be different and that's agains the rules.
    * We estimate the maximum number of validators we can see by looking at how many we have at the targets,
    * then assume that validators *unbonded* at the maximum allowed rate as we walk *backwards* along the DAG,
    * therefore at each previous rank there were as many validators as possible. Each validator can only produce
    * 1 block at each rank, but we allow 1/3rd of the validators to equivocate, just to be even more generous.
    * The goal is to catch abnormal, exponential growth, not be super realistic. This gives an arithmetic
    * progression of validator numbers, which can be used to give an upper bound on the total number of blocks
    * at any given depth.
    */
  private def notTooWide(syncState: SyncState): EitherT[F, SyncError, Unit] = {
    val total = syncState.summaries.size
    if (total < minBlockCountToCheckWidth || total <= syncState.originalTargets.size) {
      EitherT(().asRight[SyncError].pure[F])
    } else {
      // Dependencies can be scattered across many different ranks and still be at the same
      // distance when measured in hops along the j-DAG, so to use the "bonding per rank"
      // limit we need to have a different estimate for the number of depth as in ranks.
      // The max-min rank distance could be faked, but we can perhaps get an estimate by just
      // seeing how many different values we observed so far.
      val depth = syncState.ranks.size
      val maxValidatorCountAtTargets = syncState.originalTargets.map { t =>
        syncState.summaries.get(t).fold(1)(s => s.state.bonds.size)
      }.max
      // Validators can come and leave at a certain rate. If someone unbonded at every step along
      // the way we'd get as wide a graph as we can get (looking back).
      val maxValidators = maxValidatorCountAtTargets + Math.ceil(depth * maxBondingRate).toInt
      // Use the most conservative estimate by allowing 33% of the validators all equivocating
      // at every rank, and using the average maximum validator count as a higher bound.
      val maxTotal =
        Math.ceil((maxValidators + maxValidatorCountAtTargets) / 2.0 * depth * 1.33).toInt

      EitherT((TooWide(maxBondingRate, depth, maxTotal, total): SyncError).asLeft[Unit].pure[F])
        .whenA(total > maxTotal)
    }
  }

  /** Check that the new block can be reached from the current target hashes,
    * within the iterations we have are doing, using our depth-per-request setting.
    * Also check that it can be reached from the original targets within the overall limit.
    */
  private def reachable(
      syncState: SyncState,
      summary: BlockSummary,
      targetBlockHashes: Set[ByteString]
  ): EitherT[F, SyncError, (Int, Int)] = {
    val hash = summary.blockHash
    def unreachable(msg: String) =
      EitherT(
        (Unreachable(summary, maxDepthAncestorsRequest, msg): SyncError).asLeft[(Int, Int)].pure[F]
      )

    def tooDeep =
      EitherT(
        (TooDeep(Set(hash), maxPossibleDepth): SyncError).asLeft[(Int, Int)].pure[F]
      )

    // If we got here through previous children, they should already point at their parent.
    val children =
      syncState.iterationState.parentToChildren.getOrElse(hash, Set.empty)
    val iterDist = distance(hash, children, syncState.iterationState.distanceFromTargets)
    val origDist = distance(hash, children, syncState.distanceFromOriginalTargets)
    val reached  = EitherT((iterDist -> origDist).asRight[SyncError].pure[F])

    if (targetBlockHashes(hash)) {
      reached
    } else if (children.isEmpty) {
      unreachable("No children lead to this block in this iteration.")
    } else if (iterDist > maxDepthAncestorsRequest) {
      unreachable("Iteration targets too far.")
    } else if (origDist > maxPossibleDepth) {
      tooDeep
    } else {
      reached
    }
  }

  private def distance(
      hash: ByteString,
      children: Set[ByteString],
      distances: Map[ByteString, Int]
  ) =
    distances.getOrElse(hash, if (children.isEmpty) 0 else (children.map(distances).min + 1))
}

object SynchronizerImpl {
  implicit val metricsSource: Metrics.Source =
    Metrics.Source(GossipingMetricsSource, "Synchronizer")

  /** Remember that we already traversed a summary so next time we can
    * simply add the node as a new source or return as soon as we find
    * that it has been already added.
    */
  case class SyncedSummary(
      summary: BlockSummary,
      sources: Set[Node] = Set.empty
  ) {
    def addSource(source: Node) = copy(sources = sources + source)
  }

  def apply[F[_]: Concurrent: Log: Metrics](
      connectToGossip: Node => F[GossipService[F]],
      backend: SynchronizerImpl.Backend[F],
      maxPossibleDepth: Int,
      minBlockCountToCheckWidth: Int,
      maxBondingRate: Double,
      maxDepthAncestorsRequest: Int
  ) =
    for {
      semaphoreMap       <- SemaphoreMap[F, Node](1)
      syncedSummariesRef <- Ref[F].of(Map.empty[ByteString, SyncedSummary])
    } yield {
      new SynchronizerImpl[F](
        connectToGossip,
        backend,
        maxPossibleDepth,
        minBlockCountToCheckWidth,
        maxBondingRate,
        maxDepthAncestorsRequest,
        semaphoreMap,
        syncedSummariesRef
      )
    }

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("syncs", 0)
      _ <- Metrics[F].incrementCounter("syncs_failed", 0)
      _ <- Metrics[F].incrementCounter("syncs_succeeded", 0)
      _ <- Metrics[F].incrementCounter("sync_targets", 0)
      _ <- Metrics[F].incrementCounter("summaries_traversed", 0)
      _ <- Metrics[F].incrementGauge("syncs_ongoing", 0)
    } yield ()

  trait Backend[F[_]] {
    def justifications: F[List[ByteString]]
    def validate(blockSummary: BlockSummary): F[Unit]
    def notInDag(blockHash: ByteString): F[Boolean]
  }

  final case class SyncState(
      originalTargets: Set[ByteString],
      summaries: Map[ByteString, BlockSummary],
      ranks: Set[Long],
      distanceFromOriginalTargets: Map[ByteString, Int],
      parentToChildren: Map[ByteString, Set[ByteString]],
      iterationState: IterationState
  ) {
    def append(summary: BlockSummary, iterationDistance: Int, originalDistance: Int): SyncState =
      copy(
        summaries = summaries + (summary.blockHash -> summary),
        ranks = ranks + summary.rank,
        iterationState = iterationState.append(summary, iterationDistance),
        distanceFromOriginalTargets =
          distanceFromOriginalTargets.updated(summary.blockHash, originalDistance)
      )

    def endIteration = copy(
      iterationState = IterationState.empty,
      // Collect final parent relationships so we can detect missing ones.
      parentToChildren = parentToChildren |+| iterationState.parentToChildren
    )
  }
  object SyncState {
    def initial(originalTargets: Set[ByteString]) =
      SyncState(
        originalTargets,
        summaries = Map.empty,
        ranks = Set.empty,
        distanceFromOriginalTargets = Map.empty,
        parentToChildren = Map.empty,
        iterationState = IterationState.empty
      )
  }

  // Rules about how we got to see a summary are easier to check within an iteration
  // of streaming ancestors, but difficult to reason about across iterations.
  final case class IterationState(
      visited: Set[ByteString],
      distanceFromTargets: Map[ByteString, Int],
      parentToChildren: Map[ByteString, Set[ByteString]]
  ) {
    def append(summary: BlockSummary, distance: Int): IterationState = {
      val depsToChild = dependencies(summary).map(p => p -> Set(summary.blockHash)).toMap
      IterationState(
        visited = visited + summary.blockHash,
        parentToChildren = parentToChildren |+| depsToChild,
        distanceFromTargets = distanceFromTargets.updated(summary.blockHash, distance)
      )
    }
  }
  object IterationState {
    val empty = IterationState(Set.empty, Map.empty, Map.empty)
  }

  private def dependencies(summary: BlockSummary): List[ByteString] =
    (summary.justifications.map(_.latestBlockHash) ++ summary.parentHashes).toList
}
