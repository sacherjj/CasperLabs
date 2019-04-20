package io.casperlabs.comm.gossiping

import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.shared.Log

import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

// TODO: Optimise to heap-safe
class SynchronizerImpl[F[_]: Sync: Log](
    connectToGossip: Node => F[GossipService[F]],
    backend: Synchronizer.Backend[F],
    maxPossibleDepth: Int Refined Positive,
    maxPossibleWidth: Int Refined Positive,
    maxDepthAncestorsRequest: Int Refined Positive
) extends Synchronizer[F] {
  import io.casperlabs.comm.gossiping.SynchronizerImpl._

  override def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Vector[BlockSummary]] = {
    val sync = for {
      service        <- connectToGossip(source)
      tips           <- backend.tips
      justifications <- backend.justifications
      syncState      <- loop(service, targetBlockHashes.toList, tips ::: justifications, SyncState.empty)
      missing        <- missingDependencies(syncState.dag)
      newDagInTopologicalOrder <- if (missing.isEmpty)
                                   topologicalSort(syncState, targetBlockHashes).pure[F]
                                 else
                                   Log[F]
                                     .warn(s"Streamed DAG contains missing dependencies: ${missing
                                       .map(h => Base16.encode(h.toByteArray).take(6))}")
                                     .map(_ => Vector.empty[BlockSummary])
    } yield newDagInTopologicalOrder

    sync.handleErrorWith { error =>
      Log[F]
        .error(s"Failed to stream ancestor block summaries, ${error.getMessage}", error)
        .map(_ => Vector.empty[BlockSummary])
    }
  }

  private def loop(
      service: GossipService[F],
      targetBlockHashes: List[ByteString],
      knownBlockHashes: List[ByteString],
      prevSyncState: SyncState
  ): F[SyncState] =
    if (targetBlockHashes.isEmpty) {
      prevSyncState.pure[F]
    } else {
      traverse(
        service,
        targetBlockHashes,
        knownBlockHashes,
        prevSyncState
      ).flatMap(
        newSyncState =>
          missingDependencies(newSyncState.dag)
            .flatMap(
              missing =>
                if (prevSyncState.summaries == newSyncState.summaries)
                  prevSyncState.pure[F]
                else loop(service, missing, knownBlockHashes, newSyncState)
            )
      )
    }

  private def missingDependencies(dag: Map[ByteString, Set[ByteString]]): F[List[ByteString]] =
    danglingParents(dag).toList.filterA(backend.notInDag)

  private def danglingParents(dag: Map[ByteString, Set[ByteString]]): Set[ByteString] = {
    val allParents  = dag.keySet
    val allChildren = dag.values.foldLeft(Set.empty[ByteString]) { case (a, b) => a union b }
    allParents.diff(allChildren)
  }

  private def topologicalSort(
      syncState: SyncState,
      targetBlockHashes: Set[ByteString]
  ): Vector[BlockSummary] = {
    val allChildren = syncState.dag.values.foldLeft(Set.empty[ByteString]) {
      case (acc, next) => acc ++ next
    }
    val initialParents = (syncState.dag.keySet diff allChildren).toList
    val queue          = Queue(initialParents: _*)
    @annotation.tailrec
    def loop(acc: Vector[BlockSummary], q: Queue[ByteString]): Vector[BlockSummary] =
      if (q.isEmpty) {
        acc
      } else {
        val (next, nextQ) = q.dequeue
        val children      = syncState.dag.getOrElse(next, Set.empty)
        val updatedQueue  = nextQ.enqueue(children)
        //If empty then we already had it before in our DAG
        loop(acc ++ syncState.summaries.get(next), updatedQueue)
      }
    loop(Vector.empty, queue)
  }

  private def traverse(
      service: GossipService[F],
      targetBlockHashes: List[ByteString],
      knownBlockHashes: List[ByteString],
      prevSyncState: SyncState
  ): F[SyncState] =
    for {
      roundResults <- service
                       .streamAncestorBlockSummaries(
                         StreamAncestorBlockSummariesRequest(
                           targetBlockHashes = targetBlockHashes,
                           knownBlockHashes = knownBlockHashes,
                           maxDepth = maxDepthAncestorsRequest
                         )
                       )
                       .foldWhileLeftEvalL(prevSyncState.pure[F]) {
                         case (syncState, summary) =>
                           for {
                             _ <- ensure(
                                   notTooDeep(syncState.dag, targetBlockHashes.toSet),
                                   SyncError.TooDeep()
                                 )
                             _ <- ensure(notTooWide(syncState.dag), SyncError.TooWide())
                             _ <- ensure(
                                   reachable(
                                     syncState.dag,
                                     summary.blockHash,
                                     targetBlockHashes.toSet
                                   ),
                                   SyncError.NotReachable()
                                 )
                             _ <- backend.validate(summary)
                           } yield syncState.append(summary).asLeft[SyncState]
                       }
    } yield roundResults

  private def ensure(exp: => Boolean, error: => SyncError): F[Unit] =
    if (exp) {
      Sync[F].unit
    } else {
      Sync[F].raiseError[Unit](error)
    }

  private def notTooDeep(
      dag: Map[ByteString, Set[ByteString]],
      targetBlockHashes: Set[ByteString]
  ): Boolean = {
    @annotation.tailrec
    def loop(
        children: Set[ByteString],
        parents: Set[ByteString],
        counter: Int
    ): Boolean =
      if (counter === maxPossibleDepth) {
        false
      } else {
        val grandparents = parents.flatMap(dependenciesFromDag(dag, _))
        if (grandparents.isEmpty) {
          true
        } else {
          loop(parents, grandparents, counter + 1)
        }
      }
    loop(targetBlockHashes, targetBlockHashes.flatMap(dependenciesFromDag(dag, _)), 1)
  }

  private def dependenciesFromDag(
      dag: Map[ByteString, Set[ByteString]],
      hash: ByteString
  ): Set[ByteString] =
    dag.filter {
      case (_, children) => children(hash)
    }.keySet

  private def notTooWide(dag: Map[ByteString, Set[ByteString]]): Boolean = {
    val maxWidth = dag.values.foldLeft(0) { case (acc, children) => math.max(acc, children.size) }
    maxWidth < maxPossibleWidth
  }
  def f(byteString: ByteString): String = Base16.encode(byteString.toByteArray).take(4)
  private def reachable(
      dag: Map[ByteString, Set[ByteString]],
      toCheck: ByteString,
      targetBlockHashes: Set[ByteString]
  ): Boolean = {
    @annotation.tailrec
    def loop(children: Set[ByteString], counter: Int): Boolean =
      if (counter <= maxDepthAncestorsRequest && children.nonEmpty) {
        if (children(toCheck)) {
          true
        } else {
          val parents = children.flatMap(dependenciesFromDag(dag, _))
          loop(parents, counter + 1)
        }
      } else {
        false
      }
    loop(targetBlockHashes, 1)
  }
}

object SynchronizerImpl {
  sealed trait SyncError extends NoStackTrace
  object SyncError {
    final case class TooWide() extends SyncError {
      override def getMessage: String = "Returned DAG is too wide"
    }
    final case class NotReachable() extends SyncError {
      override def getMessage: String = "Returned DAG is not reachable to target block hashes"
    }
    final case class TooDeep() extends SyncError {
      override def getMessage: String = "Returned DAG is too deep"
    }
  }

  final case class SyncState(
      summaries: Map[ByteString, BlockSummary],
      dag: Map[ByteString, Set[ByteString]]
  ) {
    def append(summary: BlockSummary): SyncState =
      SyncState(
        summaries + (summary.blockHash -> summary),
        dependencies(summary).foldLeft(dag) {
          case (acc, dependency) =>
            acc + (dependency -> (acc
              .getOrElse(dependency, Set.empty[ByteString]) + summary.blockHash))
        }
      )

  }

  private def dependencies(summary: BlockSummary): List[ByteString] =
    (summary.getHeader.justifications.map(_.latestBlockHash) ++ summary.getHeader.parentHashes).toList

  object SyncState {
    val empty = SyncState(Map.empty, Map.empty)
  }
}
