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
import io.casperlabs.shared.Log

import scala.util.control.NonFatal

// TODO: Optimise to heap-safe
class SynchronizerImpl[F[_]: Sync: Log](
    connectToGossip: Node => F[GossipService[F]],
    backend: SynchronizerImpl.Backend[F],
    maxPossibleDepth: Int Refined Positive,
    startingDepthToCheckBranchingFactor: Int Refined NonNegative,
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
                missingDependencies(syncState.dag).map { missing =>
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
          missingDependencies(newSyncState.dag)
            .flatMap(
              missing =>
                if (prevSyncState.summaries == newSyncState.summaries)
                  prevSyncState.asRight[SyncError].pure[F]
                else loop(service, missing, knownBlockHashes, newSyncState)
            )
      }
    }

  private def missingDependencies(dag: Map[ByteString, Set[ByteString]]): F[List[ByteString]] =
    danglingParents(dag).toList.filterA(backend.notInDag)

  private def danglingParents(dag: Map[ByteString, Set[ByteString]]): Set[ByteString] = {
    val allParents  = dag.keySet
    val allChildren = dag.values.foldLeft(Set.empty[ByteString]) { case (a, b) => a union b }
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
          val next = current.flatMap(dependenciesFromDag(syncState.dag, _))
          loop(next)
        }
      }

    val dependencies = summary.getHeader.parentHashes.toSet ++ summary.getHeader.justifications
      .map(_.latestBlockHash)
      .toSet

    val existingDeps = syncState.summaries.keySet intersect dependencies
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

  private def dependenciesFromDag(
      dag: Map[ByteString, Set[ByteString]],
      hash: ByteString
  ): Set[ByteString] =
    dag.filter {
      case (_, children) => children(hash)
    }.keySet

  private def notTooWide(syncState: SyncState): EitherT[F, SyncError, Unit] = {
    val hashesToCheckWidth = syncState.distanceFromOriginalTarget.filter {
      case (_, distance) => distance >= startingDepthToCheckBranchingFactor
    }
    if (hashesToCheckWidth.isEmpty) {
      EitherT(().asRight[SyncError].pure[F])
    } else {
      val maybeDepth = syncState.distanceFromOriginalTarget.values.toList.maximumOption
      maybeDepth.fold(EitherT(().asRight[SyncError].pure[F])) { depth =>
        val total    = syncState.summaries.size
        val maxTotal = math.pow(maxBranchingFactor, depth.toDouble).ceil.toInt

        if (total > maxTotal) {
          EitherT((TooWide(maxBranchingFactor, maxTotal, total): SyncError).asLeft[Unit].pure[F])
        } else {
          EitherT(().asRight[SyncError].pure[F])
        }
      }
    }
  }

  private def reachable(
      syncState: SyncState,
      toCheck: BlockSummary,
      targetBlockHashes: Set[ByteString]
  ): EitherT[F, SyncError, Int] = {
    /* Returns child-to-parents map */
    def getParents(hashes: List[ByteString]): Map[ByteString, Set[ByteString]] =
      hashes
        .map(hash => hash -> dependenciesFromDag(syncState.dag, hash))
        .foldLeft(Monoid.empty[Map[ByteString, Set[ByteString]]]) { case (a, b) => a |+| Map(b) }

    @annotation.tailrec
    def loop(
        childrenToParents: Map[ByteString, Set[ByteString]],
        counter: Int
    ): EitherT[F, SyncError, Int] =
      if (counter <= maxDepthAncestorsRequest && childrenToParents.nonEmpty) {
        val maybeChildDistance = {
          val childrenDistances = childrenToParents.collect {
            case (child, parents) if parents(toCheck.blockHash) =>
              syncState.distanceFromOriginalTarget(child)
          }.toList

          if (childrenDistances.nonEmpty) {
            childrenDistances.min.some
          } else {
            None
          }
        }

        // Not using .fold because it won't be tail-recursive
        if (maybeChildDistance.nonEmpty) {
          EitherT((counter + maybeChildDistance.get).asRight[SyncError].pure[F])
        } else {
          loop(getParents(childrenToParents.values.toSet.flatten.toList), counter + 1)
        }
      } else {
        EitherT((Unreachable(toCheck, maxDepthAncestorsRequest): SyncError).asLeft[Int].pure[F])
      }

    if (syncState.originalTargets(toCheck.blockHash)) {
      EitherT(0.asRight[SyncError].pure[F])
    } else {
      loop(
        getParents(targetBlockHashes.toList) ++ syncState.dag
          .collect {
            case (parent, children) if targetBlockHashes(parent) =>
              children.map(child => child -> Set(parent)).toMap
          }
          .foldLeft(Monoid.empty[Map[ByteString, Set[ByteString]]]) {
            case (a, b) => a |+| b
          },
        1
      )
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
      dag: Map[ByteString, Set[ByteString]],
      distanceFromOriginalTarget: Map[ByteString, Int]
  ) {
    def append(summary: BlockSummary, distance: Int): SyncState =
      SyncState(
        originalTargets,
        summaries + (summary.blockHash -> summary),
        dependencies(summary).foldLeft(dag) {
          case (acc, dependency) =>
            acc + (dependency -> (acc
              .getOrElse(dependency, Set.empty[ByteString]) + summary.blockHash))
        },
        distanceFromOriginalTarget.updated(summary.blockHash, distance)
      )
  }

  private def dependencies(summary: BlockSummary): List[ByteString] =
    (summary.getHeader.justifications.map(_.latestBlockHash) ++ summary.getHeader.parentHashes).toList

  object SyncState {
    def initial(originalTargets: Set[ByteString]) =
      SyncState(originalTargets, Map.empty, Map.empty, Map.empty)
  }
}
