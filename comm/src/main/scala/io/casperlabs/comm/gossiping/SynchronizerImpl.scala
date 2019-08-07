package io.casperlabs.comm.gossiping

import cats.Monad
import cats.data._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.kernel.Monoid
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import io.casperlabs.comm.gossiping.Synchronizer.SyncError._
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import scala.util.control.NonFatal

class SynchronizerImpl[F[_]: Concurrent: Log: Metrics](
    connectToGossip: Node => F[GossipService[F]],
    backend: SynchronizerImpl.Backend[F],
    maxPossibleDepth: Int Refined Positive,
    minBlockCountToCheckWidth: Int Refined NonNegative,
    maxBondingRate: Double Refined GreaterEqual[W.`0.0`.T],
    maxDepthAncestorsRequest: Int Refined Positive,
    maxInitialBlockCount: Int Refined Positive,
    // Before the initial sync has succeeded we allow more depth.
    isInitialRef: Ref[F, Boolean],
    // Only allow 1 sync per node at a time to not traverse the same thing twice.
    sourceSemaphoresRef: Ref[F, Map[Node, Semaphore[F]]],
    // Keep the synced DAG in memory so we can avoid traversing them repeatedly.
    syncedSummariesRef: Ref[F, Map[ByteString, SynchronizerImpl.SyncedSummary]]
) extends Synchronizer[F] {
  type Effect[A] = EitherT[F, SyncError, A]

  import io.casperlabs.comm.gossiping.SynchronizerImpl._

  private def getSemaphore(source: Node): F[Semaphore[F]] =
    sourceSemaphoresRef.get.map(_.get(source)).flatMap {
      case Some(semaphore) =>
        semaphore.pure[F]
      case None =>
        for {
          s0 <- Semaphore[F](1)
          s1 <- sourceSemaphoresRef.modify { ss =>
                 ss.get(source) map { s1 =>
                   ss -> s1
                 } getOrElse {
                   ss.updated(source, s0) -> s0
                 }
               }
        } yield s1
    }

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
      tips           <- backend.tips
      justifications <- backend.justifications
      isInitial      <- isInitialRef.get
      syncStateOrError <- Metrics[F].gauge("syncs_ongoing") {
                           loop(
                             source,
                             service,
                             targetBlockHashes.toList,
                             tips ::: justifications,
                             SyncState.initial(targetBlockHashes),
                             isInitial
                           )
                         }
      res <- syncStateOrError.fold(
              _.asLeft[Vector[BlockSummary]].pure[F],
              finalizeResult(source, _)
            )
      _ <- Metrics[F].incrementCounter(if (res.isLeft) "syncs_failed" else "syncs_succeeded")
    } yield res

    getSemaphore(source).flatMap { semaphore =>
      semaphore.withPermit {
        effect.onError {
          case NonFatal(e) =>
            Log[F].error(s"Failed to sync a DAG, source: ${source.show}, reason: $e", e) *>
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
      prevSyncState: SyncState,
      isInitial: Boolean
  ): F[Either[SyncError, SyncState]] =
    if (targetBlockHashes.isEmpty) {
      prevSyncState.asRight[SyncError].pure[F]
    } else {
      traverse(
        service,
        targetBlockHashes,
        knownBlockHashes,
        prevSyncState,
        isInitial
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
                    newSyncState.copy(iteration = prevSyncState.iteration + 1),
                    isInitial
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
    danglingParents(parentToChildren).toList.filterA { blockHash =>
      for {
        syncedSummaries <- syncedSummariesRef.get
        // TODO: We could say that if we have it from *any* source, but not this,
        // then we add the new source to all dependencies and don't loop any more.
        // This could cut down traversing from sources we have not synced with before.
        isSynced  = syncedSummaries.get(blockHash).fold(false)(_.sources(source))
        isMissing <- if (isSynced) false.pure[F] else backend.notInDag(blockHash)
      } yield isMissing
    }

  /** Find parents which have no children. */
  private def danglingParents(
      parentToChildren: Map[ByteString, Set[ByteString]]
  ): Set[ByteString] = {
    val allParents = parentToChildren.keySet
    val allChildren = parentToChildren.values.foldLeft(Set.empty[ByteString]) {
      case (a, b) => a union b
    }
    allParents.diff(allChildren)
  }

  private def topologicalSort(syncState: SyncState): Vector[BlockSummary] =
    syncState.summaries.values.toVector.sortBy(_.getHeader.rank)

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
      prevSyncState: SyncState,
      isInitial: Boolean
  ): F[Either[SyncError, SyncState]] =
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
            _ <- EitherT.liftF(Metrics[F].incrementCounter("summaries_traversed"))
            distance <- reachable(
                         syncState,
                         summary,
                         targetBlockHashes.toSet
                       )
            newSyncState = syncState.append(summary, distance)
            _ <- if (isInitial) {
                  notTooManyInitial(newSyncState, summary)
                } else {
                  noCycles(syncState, summary) >>
                    notTooDeep(newSyncState) >>
                    notTooWide(newSyncState)
                }
            _ <- EitherT(
                  backend
                    .validate(summary)
                    .as(().asRight[SyncError])
                    .handleError(e => ValidationError(summary, e).asLeft[Unit])
                )
          } yield newSyncState

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
      }

  private def noCycles(syncState: SyncState, summary: BlockSummary): EitherT[F, SyncError, Unit] = {
    def loop(current: Set[ByteString]): EitherT[F, SyncError, Unit] =
      if (current.isEmpty) {
        EitherT(().asRight[SyncError].pure[F])
      } else {
        if (current(summary.blockHash)) {
          EitherT((Cycle(summary): SyncError).asLeft[Unit].pure[F])
        } else {
          val next = current.flatMap(syncState.childToParents(_))
          loop(next)
        }
      }

    val deps = dependencies(summary).toSet

    val existingDeps = syncState.summaries.keySet intersect deps
    if (existingDeps.nonEmpty) {
      loop(existingDeps)
    } else {
      EitherT(().asRight[SyncError].pure[F])
    }
  }

  private def notTooManyInitial(
      syncState: SyncState,
      last: BlockSummary
  ): EitherT[F, SyncError, Unit] =
    if (syncState.summaries.size <= maxInitialBlockCount) {
      EitherT(().asRight[SyncError].pure[F])
    } else {
      EitherT((TooMany(last.blockHash, maxInitialBlockCount): SyncError).asLeft[Unit].pure[F])
    }

  private def notTooDeep(syncState: SyncState): EitherT[F, SyncError, Unit] =
    if (syncState.distanceFromOriginalTarget.isEmpty) {
      EitherT(().asRight[SyncError].pure[F])
    } else {
      val exceeded = syncState.distanceFromOriginalTarget.collect {
        case (hash, distance) if distance > maxPossibleDepth => hash
      }.toSet
      if (exceeded.isEmpty) {
        EitherT(().asRight[SyncError].pure[F])
      } else {
        EitherT((TooDeep(exceeded, maxPossibleDepth): SyncError).asLeft[Unit].pure[F])
      }
    }

  private def notTooWide(syncState: SyncState): EitherT[F, SyncError, Unit] = {
    val depth = syncState.distanceFromOriginalTarget.values.toList.maximumOption.getOrElse(0)
    val maxValidatorCountAtTargets = syncState.originalTargets.map { t =>
      syncState.summaries.get(t).fold(1)(s => s.getHeader.getState.bonds.size)
    }.max
    // Validators can come and leave at a certain rate. If someone unbonded at every step along
    // the way we'd get as wide a graph as we can get.
    val maxValidators = maxValidatorCountAtTargets + Math.ceil(depth * maxBondingRate).toInt
    // Estimate the most conservative estimate to be with 33% of the validators all equivocating
    // at every rank, and use the average maximum validator count as a higher bound.
    val maxTotal = Math.ceil((maxValidators + maxValidatorCountAtTargets) / 2 * depth * 1.33).toInt
    val total    = syncState.summaries.size
    if (total > minBlockCountToCheckWidth &&
        total > syncState.originalTargets.size &&
        total > maxTotal) {
      EitherT((TooWide(maxBondingRate, depth, maxTotal, total): SyncError).asLeft[Unit].pure[F])
    } else {
      EitherT(().asRight[SyncError].pure[F])
    }
  }

  /** Check that `toCheck` can be reached from the current original target
    * within the iterations we have done using our depth-per-request setting.
    */
  private def reachable(
      syncState: SyncState,
      toCheck: BlockSummary,
      targetBlockHashes: Set[ByteString]
  ): EitherT[F, SyncError, Int] = {
    def unreachable(msg: String) =
      EitherT((Unreachable(toCheck, maxDepthAncestorsRequest, msg): SyncError).asLeft[Int].pure[F])

    // Check that we can reach a target within the request depth.
    def targetReachable(i: Int, front: Set[ByteString]): Boolean =
      if (front.intersect(targetBlockHashes).nonEmpty) {
        true
      } else if (i > maxDepthAncestorsRequest) {
        false
      } else {
        val nextFront =
          front.flatMap(syncState.parentToChildren.getOrElse(_, Set.empty)).diff(front)
        targetReachable(i + 1, nextFront)
      }

    if (syncState.originalTargets(toCheck.blockHash)) {
      EitherT(0.asRight[SyncError].pure[F])
    } else {
      // If we got here it should have been through a child, which should already have a distance.
      val children = syncState.parentToChildren.getOrElse(toCheck.blockHash, Set.empty)
      if (children.isEmpty) {
        unreachable("No children lead to this block.")
      } else {
        if (!targetReachable(0, Set(toCheck.blockHash))) {
          unreachable("None of the iteration targets are reachable.")
        } else {
          val distFromOriginal = children.map(syncState.distanceFromOriginalTarget).min + 1
          EitherT((distFromOriginal).asRight[SyncError].pure[F])
        }
      }
    }
  }
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
      maxPossibleDepth: Int Refined Positive,
      minBlockCountToCheckWidth: Int Refined NonNegative,
      maxBondingRate: Double Refined GreaterEqual[W.`0.0`.T],
      maxDepthAncestorsRequest: Int Refined Positive,
      maxInitialBlockCount: Int Refined Positive,
      isInitialRef: Ref[F, Boolean]
  ) =
    for {
      semaphoresRef      <- Ref[F].of(Map.empty[Node, Semaphore[F]])
      syncedSummariesRef <- Ref[F].of(Map.empty[ByteString, SyncedSummary])
    } yield {
      new SynchronizerImpl[F](
        connectToGossip,
        backend,
        maxPossibleDepth,
        minBlockCountToCheckWidth,
        maxBondingRate,
        maxDepthAncestorsRequest,
        maxInitialBlockCount,
        isInitialRef,
        semaphoresRef,
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
    def tips: F[List[ByteString]]
    def justifications: F[List[ByteString]]
    def validate(blockSummary: BlockSummary): F[Unit]
    def notInDag(blockHash: ByteString): F[Boolean]
  }

  final case class SyncState(
      iteration: Int,
      originalTargets: Set[ByteString],
      summaries: Map[ByteString, BlockSummary],
      parentToChildren: Map[ByteString, Set[ByteString]],
      childToParents: Map[ByteString, Set[ByteString]],
      distanceFromOriginalTarget: Map[ByteString, Int]
  ) {
    def append(summary: BlockSummary, distance: Int): SyncState = {
      val deps = dependencies(summary)
      SyncState(
        iteration,
        originalTargets,
        summaries + (summary.blockHash -> summary),
        parentToChildren = deps.foldLeft(parentToChildren) {
          case (acc, dependency) =>
            acc + (dependency -> (acc(dependency) + summary.blockHash))
        },
        childToParents = deps.foldLeft(childToParents) {
          case (acc, dependency) =>
            acc + (summary.blockHash -> (acc(summary.blockHash) + dependency))
        },
        distanceFromOriginalTarget.updated(summary.blockHash, distance)
      )
    }
  }

  private def dependencies(summary: BlockSummary): List[ByteString] =
    (summary.getHeader.justifications.map(_.latestBlockHash) ++ summary.getHeader.parentHashes).toList

  object SyncState {
    def initial(originalTargets: Set[ByteString]) =
      SyncState(
        iteration = 1,
        originalTargets,
        summaries = Map.empty,
        parentToChildren = Map.empty.withDefaultValue(Set.empty),
        childToParents = Map.empty.withDefaultValue(Set.empty),
        distanceFromOriginalTarget = Map.empty
      )
  }
}
