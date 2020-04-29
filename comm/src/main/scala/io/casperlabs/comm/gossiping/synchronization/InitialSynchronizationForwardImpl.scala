package io.casperlabs.comm.gossiping.synchronization

import cats.Parallel
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.GossipError
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.downloadmanager.BlockDownloadManager
import io.casperlabs.comm.gossiping.synchronization.InitialSynchronization.SynchronizationError
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.shared.IterantOps.RichIterant
import io.casperlabs.shared.Log

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

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
  * @param rankStartFrom Initial value of rank to start syncing with peers. Usually, the minimum rank of latest messages.
  * @param roundPeriod   Time to wait between period, useful to configure "aggressiveness" of the initial synchronization
  */
class InitialSynchronizationForwardImpl[F[_]: Parallel: Log: Timer](
    nodeDiscovery: NodeDiscovery[F],
    selectNodes: List[Node] => List[Node],
    memoizeNodes: Boolean,
    connector: GossipService.Connector[F],
    minSuccessful: Int,
    skipFailedNodesInNextRounds: Boolean,
    // Schedule items with the DownloadManager.
    downloadManager: BlockDownloadManager[F],
    // Handle missing dependencies by doing a normal backwards going synchronization.
    synchronizer: Synchronizer[F],
    step: Int,
    rankStartFrom: Long,
    roundPeriod: FiniteDuration
)(implicit F: Concurrent[F])
    extends InitialSynchronization[F] {
  override def sync(): F[WaitHandle[F]] = {

    /* The DownloadManager validates the dependencies on scheduling, so no need to check them here,
     * but it's possible that the node might have received a message in range [N, N+100] that depends
     * on a message that wasn't there previously in the [N-100, N] range.
     * We can try to sync such messages using the regular synchronizer.
     *
     * For example say we go in rank ranges [0-4],[5-9] and in our first round we get blocks [G,A,B,C,D],
     * but by the time we finish downloading them and move on to [5-9] there are some new block in the [0-4]
     * range. We get [F,L,G,H,I] in the slice, but L is rejected because K is missing. We use the Synchronizer
     * to get the missing DAG part [J,K], download them, then try L again.
     * G - A - B - C - D -|- F - G - H - I
     *           \ J - K -|- L
     * */
    def schedule(peer: Node, summary: BlockSummary): F[WaitHandle[F]] = {
      val download = downloadManager.scheduleDownload(summary, peer, relay = false)

      // Map over the wait handle returned by the schedule without waiting on it,
      // so that we can schedule the rest of them (some of it can be downloaddd in parallel).
      download.map { handle =>
        // Attach an error handler to the handle.
        handle.recoverWith {
          case GossipError.MissingDependencies(_, missing) =>
            for {
              missingDag <- synchronizer.syncDag(peer, missing.toSet).rethrow
              missingHandles <- missingDag.traverse { dep =>
                                 downloadManager.scheduleDownload(dep, peer, relay = false)
                               }
              // Wait for all these extra backfilling downloads to finish.
              _ <- missingHandles.sequence
              // Now try the original download again.
              retryHandle <- download
              // Whoever is waiting on `handle` now has to wait on the `retryHandle` instead.
              _ <- retryHandle
            } yield ()
        }
      }
    }

    /** Returns
      * - max returned rank
      * - 'true' if the DAG is fully synced with the peer or 'false' otherwise */
    def syncDagSlice(peer: Node, jRank: Long): F[(Long, Boolean)] = {
      // 1. previously seen hashes
      // 2. summaries for scheduling
      // 3. max rank from a batch
      type S = (Set[ByteString], Vector[BlockSummary], Long)
      val emptyS: S = (Set.empty, Vector.empty, jRank)

      for {
        gossipService <- connector(peer)
        (_, summaries, maxRank) <- gossipService
                                    .streamDagSliceBlockSummaries(
                                      StreamDagSliceBlockSummariesRequest(
                                        startRank = jRank,
                                        endRank = jRank + step
                                      )
                                    )
                                    .foldLeftM(emptyS) {
                                      case ((prevHashes, summaries, maxRank), s) =>
                                        val h = s.blockHash
                                        val r = s.jRank
                                        for {
                                          _ <- F.whenA(r < jRank || r > jRank + step) {
                                                val hexHash = hex(h)
                                                Log[F].error(
                                                  s"Failed to sync with ${peer.show -> "show"}, rank of summary $hexHash $r not in range of [${jRank -> "from"}, ${jRank + step -> "to"}]"
                                                ) >> F.raiseError(
                                                  SynchronizationError()
                                                )
                                              }
                                          _ <- F.whenA(prevHashes(h)) {
                                                val hexHash = hex(h)
                                                Log[F].error(
                                                  s"Failed to sync with ${peer.show -> "show"}, $hexHash has seen previously in range of [${jRank -> "from"}, ${jRank + step -> "to"}]"
                                                ) >>
                                                  F.raiseError(
                                                    SynchronizationError()
                                                  )
                                              }
                                          newSeenHashes = prevHashes + h
                                          newSummaries  = summaries :+ s
                                          newMaxRank    = math.max(maxRank, r)
                                        } yield (newSeenHashes, newSummaries, newMaxRank)
                                    }
        fullySynced = maxRank < jRank + step

        // Schedule all in topological order. Do this outside the loop that consumed the slice so as not to keep up the other side.
        handles <- summaries.traverse(schedule(peer, _))

        // Wait for all the downloads to finish.
        _ <- handles.sequence.onError {
              case NonFatal(ex) =>
                Log[F].error(
                  s"Failed to sync with ${peer.show -> "peer"}: $ex"
                )
            }

      } yield (maxRank, fullySynced)
    }

    def loop(nodes: List[Node], failed: Set[Node], rank: Long): F[Unit] = {
      // 1. fully synced nodes num
      // 2. successfully responded nodes
      // 3. nodes failed to respond
      // 4. max synced rank from the previous round
      type S = (Int, List[Node], Set[Node], Long)
      val emptyS: S = (0, Nil, failed, rank)
      for {
        _ <- F.whenA(nodes.isEmpty && failed.nonEmpty)(
              Log[F].error("Failed to run initial sync - no more nodes to try") >>
                F.raiseError(SynchronizationError())
            )
        _ <- Log[F].debug(s"Next round of syncing with nodes: ${nodes.map(_.show) -> "peers"}")

        _ <- Log[F].info(s"Syncing with ${nodes.size -> "nodes"} from $rank")
        // Sync in parallel
        results <- nodes.parTraverse(n => syncDagSlice(n, rank).attempt.map(result => n -> result))

        (fullSyncs, successful, newFailed, prevRoundRank) = results.foldLeft(emptyS) {
          case ((i, s, f, r), (node, Right((prevRank, isFull)))) =>
            (i + (if (isFull) 1 else 0), node :: s, f, math.max(r, prevRank))

          case ((i, s, f, r), (node, Left(_))) =>
            (i, s, f + node, r)
        }

        _ <- if (fullSyncs >= minSuccessful) {
              Log[F].info(
                s"Successfully synced with $fullSyncs nodes, required: $minSuccessful"
              )
            } else {
              val nextRoundNodes =
                if (memoizeNodes) {
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
              Log[F].info(
                s"Haven't reached required $minSuccessful amount of fully synced nodes, currently at $fullSyncs, continuing initial synchronization."
              ) >>
                Timer[F].sleep(roundPeriod) >> nextRoundNodes >>= {
                loop(_, if (skipFailedNodesInNextRounds) newFailed else Set.empty, prevRoundRank)
              }
            }
      } yield ()
    }

    nodeDiscovery.recentlyAlivePeersAscendingDistance
      .flatMap { peers =>
        val nodesToSyncWith = selectNodes(peers)
        //TODO: Another unsafe cast to Int
        loop(nodesToSyncWith, failed = Set.empty, rank = rankStartFrom)
      }
      .start
      .map(_.join)

  }
}
