package io.casperlabs.comm.gossiping

import cats.data._
import cats.effect._
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
import io.casperlabs.comm.gossiping.Synchronizer.SyncError.{
  Cycle,
  MissingDependencies,
  TooDeep,
  TooWide,
  Unreachable,
  ValidationError
}
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.shared.Log

import scala.util.control.NonFatal

// TODO: Optimise to heap-safe
class SynchronizerImpl[F[_]: Sync: Log](
    connectToGossip: Node => F[GossipService[F]],
    backend: SynchronizerImpl.Backend[F],
    maxPossibleDepth: Int Refined Positive,
    minBlockCountToCheckBranchingFactor: Int Refined NonNegative,
    maxBranchingFactor: Double Refined GreaterEqual[W.`1.0`.T],
    maxDepthAncestorsRequest: Int Refined Positive
) extends Synchronizer[F] {
  type Effect[A] = EitherT[F, SyncError, A]

  import io.casperlabs.comm.gossiping.SynchronizerImpl._

  override def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Either[SyncError, Vector[BlockSummary]]] = {
    val effect = for {
      service        <- connectToGossip(source)
      tips           <- backend.tips
      justifications <- backend.justifications
      syncStateOrError <- loop(
                           service,
                           targetBlockHashes.toList,
                           tips ::: justifications,
                           SyncState.initial(targetBlockHashes)
                         )
      res <- syncStateOrError.fold(
              _.asLeft[Vector[BlockSummary]].pure[F], { syncState =>
                missingDependencies(syncState.parentToChildren).map { missing =>
                  if (missing.isEmpty) {
                    topologicalSort(syncState).asRight[SyncError]
                  } else {
                    MissingDependencies(missing.toSet).asLeft[Vector[BlockSummary]]
                  }
                }
              }
            )
    } yield res
    effect.onError {
      case NonFatal(e) =>
        Log[F].error(s"Failed to sync a DAG, source: ${source.show}, reason: $e", e)
    }
  }

  private def loop(
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
          missingDependencies(newSyncState.parentToChildren)
            .flatMap(
              missing =>
                if (prevSyncState.summaries == newSyncState.summaries)
                  prevSyncState.asRight[SyncError].pure[F]
                else loop(service, missing, knownBlockHashes, newSyncState)
            )
      }
    }

  private def missingDependencies(
      parentToChildren: Map[ByteString, Set[ByteString]]
  ): F[List[ByteString]] =
    danglingParents(parentToChildren).toList.filterA(backend.notInDag)

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

  private def traverse(
      service: GossipService[F],
      targetBlockHashes: List[ByteString],
      knownBlockHashes: List[ByteString],
      prevSyncState: SyncState
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
            _ <- noCycles(syncState, summary)
            distance <- reachable(
                         syncState,
                         summary,
                         targetBlockHashes.toSet
                       )
            newSyncState = syncState.append(summary, distance)
            _            <- notTooDeep(newSyncState)
            _            <- notTooWide(newSyncState)

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

  // TODO: Rewrite this check to be based on the number of validators:
  // at any rank the DAG cannot be wider then the number of validators,
  // otherwise some of them would be equivocating (which is fine, up to a threshold of 0.33).
  private def notTooWide(syncState: SyncState): EitherT[F, SyncError, Unit] = {
    val depth = syncState.distanceFromOriginalTarget.values.toList.maximumOption.getOrElse(0)
    val maxTotal =
      if (maxBranchingFactor <= 1) depth
      else {
        ((math.pow(maxBranchingFactor, depth.toDouble) - 1) / (maxBranchingFactor - 1)).ceil.toInt
      }
    val total = syncState.summaries.size
    if (total > minBlockCountToCheckBranchingFactor &&
        total > syncState.originalTargets.size &&
        total > maxTotal) {
      EitherT((TooWide(maxBranchingFactor, maxTotal, total): SyncError).asLeft[Unit].pure[F])
    } else {
      EitherT(().asRight[SyncError].pure[F])
    }
  }

  /** Check that `toCheck` can be reached from the current `targetBlockHashes` within the distance
    * of a single `maxDepthAncestorsRequest`. If so, return the distance to the original targets,
    * i.e. the ones that can be multiple recursive steps away already. */
  private def reachable(
      syncState: SyncState,
      toCheck: BlockSummary,
      targetBlockHashes: Set[ByteString]
  ): EitherT[F, SyncError, Int] = {
    // One map with all the children's parents in it.
    def getParents(children: List[ByteString]): Map[ByteString, Set[ByteString]] =
      children
        .map(hash => Map(hash -> syncState.childToParents(hash)))
        .foldLeft(Monoid.empty[Map[ByteString, Set[ByteString]]]) { case (a, b) => a |+| b }

    // Start with the current targets and move towards their children until we find
    // `toCheck`, or go farther then the allowed distance.
    @annotation.tailrec
    def loop(
        childrenToParents: Map[ByteString, Set[ByteString]],
        counter: Int
    ): EitherT[F, SyncError, Int] =
      if (counter <= maxDepthAncestorsRequest && childrenToParents.nonEmpty) {
        val maybeChildOfToCheckDistance = {
          val childrenDistances = childrenToParents.collect {
            case (child, parents) if parents(toCheck.blockHash) =>
              syncState.distanceFromOriginalTarget(child)
          }.toList

          if (childrenDistances.nonEmpty) {
            Some(childrenDistances.min)
          } else {
            None
          }
        }

        // Not using .fold because it won't be tail-recursive
        if (maybeChildOfToCheckDistance.nonEmpty) {
          EitherT((counter + maybeChildOfToCheckDistance.get + 1).asRight[SyncError].pure[F])
        } else {
          loop(getParents(childrenToParents.values.toSet.flatten.toList), counter + 1)
        }
      } else {
        EitherT((Unreachable(toCheck, maxDepthAncestorsRequest): SyncError).asLeft[Int].pure[F])
      }

    if (syncState.originalTargets(toCheck.blockHash)) {
      EitherT(0.asRight[SyncError].pure[F])
    } else {
      // `toCheck` can be one of the targets, so start with the parents and walk from there; 0 distance
      val targetsToParents = getParents(targetBlockHashes.toList)

      // If `toCheck` is not part of the original targets then there must have been something
      // that lead us here and that will already have its distance established. Start from there.
      val childrenOfTargets = getParents(
        targetBlockHashes.flatMap(syncState.parentToChildren(_)).toList
      )

      // Not sure if the same `counter` value should apply to both of those groups.
      // But with counter=1 and maxDepthAncestorsRequest=1 it failed to sync some block in HashSetCasperTest.
      loop(targetsToParents ++ childrenOfTargets, counter = 0)
    }
  }
}

object SynchronizerImpl {
  trait Backend[F[_]] {
    def tips: F[List[ByteString]]
    def justifications: F[List[ByteString]]
    def validate(blockSummary: BlockSummary): F[Unit]
    def notInDag(blockHash: ByteString): F[Boolean]
  }

  final case class SyncState(
      originalTargets: Set[ByteString],
      summaries: Map[ByteString, BlockSummary],
      parentToChildren: Map[ByteString, Set[ByteString]],
      childToParents: Map[ByteString, Set[ByteString]],
      distanceFromOriginalTarget: Map[ByteString, Int]
  ) {
    def append(summary: BlockSummary, distance: Int): SyncState =
      SyncState(
        originalTargets,
        summaries + (summary.blockHash -> summary),
        parentToChildren = dependencies(summary).foldLeft(parentToChildren) {
          case (acc, dependency) =>
            acc + (dependency -> (acc(dependency) + summary.blockHash))
        },
        childToParents = dependencies(summary).foldLeft(childToParents) {
          case (acc, dependency) =>
            acc + (summary.blockHash -> (acc(summary.blockHash) + dependency))
        },
        distanceFromOriginalTarget.updated(summary.blockHash, distance)
      )
  }

  private def dependencies(summary: BlockSummary): List[ByteString] =
    (summary.getHeader.justifications.map(_.latestBlockHash) ++ summary.getHeader.parentHashes).toList

  object SyncState {
    def initial(originalTargets: Set[ByteString]) =
      SyncState(
        originalTargets,
        summaries = Map.empty,
        parentToChildren = Map.empty.withDefaultValue(Set.empty),
        childToParents = Map.empty.withDefaultValue(Set.empty),
        distanceFromOriginalTarget = Map.empty
      )
  }
}
