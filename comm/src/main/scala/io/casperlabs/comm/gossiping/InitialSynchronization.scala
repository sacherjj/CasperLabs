package io.casperlabs.comm.gossiping

import cats.Parallel
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.InitialSynchronizationImpl.SynchronizationError
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.shared.Log

trait InitialSynchronization[F[_]] {

  /**
    * Synchronizes the node with the last tips.
    *
    * @return Handle which will be resolved when node is considered to be fully synced
    */
  def sync(): F[WaitHandle[F]]
}

/**
  * Synchronizes the node with peers in rounds by fetching slices of the DAG
  * as BlockSummaries and then scheduling them in the DownloadManager
  * until [[minSuccessful]] nodes considered as fully synced with in some round.
  *
  * @param selectNodes   Filtering function to select nodes to synchronize with in a round.
  *                      Second arg is a list of all known peers excluding bootstrap
  * @param memoizeNodes  If true applies [[selectNodes]] function only once and stores results for next rounds
  *                      Otherwise, invokes it each round
  * @param minSuccessful Minimal number of successful responses in a round to consider synchronisation as successful
  * @param step          Depth of DAG slices (by rank) retrieved slice-by-slice until node fully synchronized
  */
class InitialSynchronizationImpl[F[_]: Parallel: Log](
    nodeDiscovery: NodeDiscovery[F],
    selectNodes: List[Node] => List[Node],
    memoizeNodes: Boolean,
    connector: GossipService.Connector[F],
    minSuccessful: Int,
    skipFailedNodesInNextRounds: Boolean,
    downloadManager: DownloadManager[F],
    step: Int
)(implicit F: Concurrent[F])
    extends InitialSynchronization[F] {
  override def sync(): F[WaitHandle[F]] = {

    /* DownloadManager validates dependencies on scheduling, so no need to check them here */
    def schedule(p: Node, s: BlockSummary): F[WaitHandle[F]] =
      downloadManager.scheduleDownload(s, p, relay = false)

    /* Returns 'true' if the DAG is fully synced with the peer */
    def syncDagSlice(peer: Node, rank: Int): F[Boolean] = {
      // 1. previously seen hashes
      // 2. wait handlers from DownloadManager
      // 3. max rank from a batch
      type S = (Set[ByteString], Vector[WaitHandle[F]], Long)
      val emptyS: S = (Set.empty, Vector.empty, -1)

      for {
        gossipService <- connector(peer)
        (_, handlers, maxRank) <- gossipService
                                   .streamDagSliceBlockSummaries(
                                     StreamDagSliceBlockSummariesRequest(
                                       startRank = rank,
                                       endRank = rank + step
                                     )
                                   )
                                   .foldWhileLeftEvalL(F.pure(emptyS)) {
                                     case ((prevHashes, handlers, maxRank), s) =>
                                       val h = s.blockHash
                                       val r = s.rank
                                       for {
                                         _ <- F.whenA(r < rank || r > rank + step) {
                                               val hexHash = hex(h)
                                               val m =
                                                 s"Failed to sync with ${peer.show}, rank of summary $hexHash $r not in range of [$rank, ${rank + step}]"
                                               Log[F].error(m) >> F.raiseError(
                                                 SynchronizationError()
                                               )
                                             }
                                         _ <- F.whenA(prevHashes(h)) {
                                               val hexHash = hex(h)
                                               val m =
                                                 s"Failed to sync with ${peer.show}, $hexHash has seen previously in range of [$rank, ${rank + step}]"
                                               Log[F].error(m) >>
                                                 F.raiseError(
                                                   SynchronizationError()
                                                 )
                                             }
                                         wh            <- schedule(peer, s)
                                         newSeenHashes = prevHashes + h
                                         newHandlers   = handlers :+ wh
                                         newMaxRank    = math.max(maxRank, r)
                                       } yield (newSeenHashes, newHandlers, newMaxRank)
                                         .asLeft[S]
                                   }
        fullySynced = maxRank < rank + step
        _           <- handlers.sequence
      } yield fullySynced
    }

    def loop(nodes: List[Node], failed: Set[Node], rank: Int): F[Unit] = {
      // 1. fully synced nodes num
      // 2. successfully responded nodes
      // 3. nodes failed to respond
      type S = (Int, List[Node], Set[Node])
      val emptyS: S = (0, Nil, failed)
      for {
        _ <- F.whenA(nodes.isEmpty && failed.nonEmpty)(
              Log[F].error("Failed to run initial sync - no more nodes to try") >>
                F.raiseError(SynchronizationError())
            )
        _ <- Log[F].debug(s"Next round of syncing with nodes: ${nodes.map(_.show)}")
        // Sync in parallel
        results <- nodes.parTraverse(n => syncDagSlice(n, rank).attempt.map(result => n -> result))
        (fullSyncs, successful, newFailed) = results.foldLeft(emptyS) {
          case ((i, s, f), (node, Right(true)))  => (i + 1, node :: s, f)
          case ((i, s, f), (node, Right(false))) => (i, node :: s, f)
          case ((i, s, f), (node, Left(_)))      => (i, s, f + node)
        }
        _ <- if (fullSyncs >= minSuccessful) {
              Log[F].debug(
                s"Successfully synced with $fullSyncs nodes, required: $minSuccessful"
              )
            } else {
              for {
                nextRoundNodes <- if (memoizeNodes) {
                                   F.pure(if (skipFailedNodesInNextRounds) successful else nodes)
                                 } else {
                                   nodeDiscovery.recentlyAlivePeersAscendingDistance.map { peers =>
                                     val nodes = selectNodes(peers)
                                     if (skipFailedNodesInNextRounds) {
                                       nodes.filterNot(newFailed)
                                     } else {
                                       nodes
                                     }
                                   }
                                 }
                _ <- Log[F].debug(
                      s"Haven't reached required $minSuccessful amount of fully synced nodes, " +
                        "continuing initial synchronization"
                    )
                _ <- loop(
                      nextRoundNodes,
                      if (skipFailedNodesInNextRounds) newFailed else Set.empty,
                      rank + step + 1
                    )
              } yield ()
            }
      } yield ()
    }

    nodeDiscovery.recentlyAlivePeersAscendingDistance
      .flatMap { peers =>
        val nodesToSyncWith = selectNodes(peers)
        loop(nodesToSyncWith, failed = Set.empty, rank = 0)
      }
      .start
      .map(_.join)

  }
}

object InitialSynchronizationImpl {
  final case class SynchronizationError() extends Exception
}
