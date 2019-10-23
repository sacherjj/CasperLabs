package io.casperlabs.comm.gossiping

import cats.Parallel
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.InitialSynchronization.SynchronizationError
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.comm.gossiping.synchronization.Synchronizer
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.shared.Log
import io.casperlabs.shared.IterantOps.RichIterant

import scala.concurrent.duration.FiniteDuration

trait InitialSynchronization[F[_]] {

  /**
    * Synchronizes the node with the last tips.
    *
    * @return Handle which will be resolved when node is considered to be fully synced
    */
  def sync(): F[WaitHandle[F]]
}

object InitialSynchronization {
  final case class SynchronizationError() extends Exception
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
  * @param rankStartFrom Initial value of rank to start syncing with peers. Usually, the minimum rank of latest messages.
  */
class InitialSynchronizationForwardImpl[F[_]: Parallel: Log](
    nodeDiscovery: NodeDiscovery[F],
    selectNodes: List[Node] => List[Node],
    memoizeNodes: Boolean,
    connector: GossipService.Connector[F],
    minSuccessful: Int,
    skipFailedNodesInNextRounds: Boolean,
    downloadManager: DownloadManager[F],
    step: Int,
    rankStartFrom: Long
)(implicit F: Concurrent[F])
    extends InitialSynchronization[F] {
  override def sync(): F[WaitHandle[F]] = {

    /* DownloadManager validates dependencies on scheduling, so no need to check them here */
    def schedule(p: Node, s: BlockSummary): F[WaitHandle[F]] =
      downloadManager.scheduleDownload(s, p, relay = false)

    /** Returns
      * - max returned rank
      * - 'true' if the DAG is fully synced with the peer or 'false' otherwise */
    def syncDagSlice(peer: Node, rank: Int): F[(Int, Boolean)] = {
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
                                   .foldLeftM(emptyS) {
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
                                   }
        fullySynced = maxRank < rank + step
        _           <- handlers.sequence
        //TODO: Maybe use int64 protobuf type for StreamDagSliceBlockSummariesRequest to use Long everywhere?
        //      Or start dealing with complex specifics of protobuf unsigned numbers encodings in Java?
        //      https://developers.google.com/protocol-buffers/docs/proto3#scalar
        //      Unsafe and dirty hack is used now here.
        _ <- F.whenA(maxRank > Int.MaxValue) {
              val m =
                s"Failed to sync with ${peer.show}, returned rank $maxRank is higher than Int.MaxValue"
              Log[F].error(m) >> F.raiseError(SynchronizationError())
            }
      } yield (maxRank.toInt, fullySynced)
    }

    def loop(nodes: List[Node], failed: Set[Node], rank: Int): F[Unit] = {
      // 1. fully synced nodes num
      // 2. successfully responded nodes
      // 3. nodes failed to respond
      // 4. max synced rank from the previous round
      type S = (Int, List[Node], Set[Node], Int)
      val emptyS: S = (0, Nil, failed, -1)
      for {
        _ <- F.whenA(nodes.isEmpty && failed.nonEmpty)(
              Log[F].error("Failed to run initial sync - no more nodes to try") >>
                F.raiseError(SynchronizationError())
            )
        _ <- Log[F].debug(s"Next round of syncing with nodes: ${nodes.map(_.show)}")
        // Sync in parallel
        results <- nodes.parTraverse(n => syncDagSlice(n, rank).attempt.map(result => n -> result))
        (fullSyncs, successful, newFailed, prevRoundRank) = results.foldLeft(emptyS) {
          case ((i, s, f, r), (node, Right((prevRank, true)))) =>
            (i + 1, node :: s, f, math.max(r, prevRank))
          case ((i, s, f, r), (node, Right((prevRank, false)))) =>
            (i, node :: s, f, math.max(r, prevRank))
          case ((i, s, f, r), (node, Left(_))) => (i, s, f + node, r)
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
                      prevRoundRank
                    )
              } yield ()
            }
      } yield ()
    }

    nodeDiscovery.recentlyAlivePeersAscendingDistance
      .flatMap { peers =>
        val nodesToSyncWith = selectNodes(peers)
        //TODO: Another unsafe cast to Int
        loop(nodesToSyncWith, failed = Set.empty, rank = rankStartFrom.toInt)
      }
      .start
      .map(_.join)

  }
}

/**
  * Synchronizes the node with peers in rounds until [[minSuccessful]] nodes
  * respond successfully in some round.
  *
  * Uses backward walking algorithm:
  *  1. Ask a peer for tips
  *  2. Notify ourselves about tips as new blocks
  *  3. Local GossipServiceServer will schedule backward synchronization using [[Synchronizer]].
  *
  * @param selectNodes   Filtering function to select nodes to synchronize with in a round.
  *                      Second arg is a list of all known peers excluding bootstrap
  * @param memoizeNodes  If true applies [[selectNodes]] function only once and stores results for next rounds
  *                      Otherwise, invokes it each round
  * @param minSuccessful Minimal number of successful responses in a round to consider synchronisation as successful
  * @param roundPeriod   Delay between synchronisation rounds
  */
class InitialSynchronizationBackwardImpl[F[_]: Concurrent: Parallel: Log: Timer](
    nodeDiscovery: NodeDiscovery[F],
    selfGossipService: GossipServiceServer[F],
    selectNodes: List[Node] => List[Node],
    memoizeNodes: Boolean,
    connector: GossipService.Connector[F],
    minSuccessful: Int,
    skipFailedNodesInNextRounds: Boolean,
    roundPeriod: FiniteDuration
) extends InitialSynchronization[F] {
  override def sync(): F[WaitHandle[F]] = {
    def sync(node: Node): F[Unit] =
      for {
        service <- connector(node)
        _       <- Log[F].debug(s"Syncing with a node: ${node.show}")
        tips    <- service.streamDagTipBlockSummaries(StreamDagTipBlockSummariesRequest()).toListL
        _ <- MonadThrowable[F].rethrow {
              selfGossipService
                .newBlocksSynchronous(
                  NewBlocksRequest(
                    sender = node.some,
                    blockHashes = tips.map(_.blockHash)
                  ),
                  skipRelaying = true
                )
                .map(_.leftWiden[Throwable])
            }
      } yield ()

    def loop(nodes: List[Node], failed: Set[Node]): F[Unit] =
      if (nodes.isEmpty && failed.nonEmpty) {
        Log[F].error("Failed to run initial sync - no more nodes to try") >>
          Sync[F].raiseError(SynchronizationError())
      } else {
        Log[F].debug(s"Next round of syncing with nodes: ${nodes.map(_.show)}") >>
          nodes
            .traverse(node => sync(node).attempt.map(result => node -> result))
            .flatMap { results =>
              val (successfulE, failedE) = results.partition(_._2.isRight)
              val successful             = successfulE.map(_._1)
              val newFailed              = failed ++ failedE.map(_._1)

              if (successful.size >= minSuccessful) {
                Log[F].debug(
                  s"Successfully synced with ${successful.size} nodes, required: $minSuccessful"
                )
              } else {
                val nextRoundNodes =
                  if (memoizeNodes) {
                    (if (skipFailedNodesInNextRounds) successful else nodes).pure[F]
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
                Log[F].debug(
                  s"Haven't reached required $minSuccessful amount of nodes, retrying in $roundPeriod"
                ) >>
                  Timer[F].sleep(roundPeriod) >> nextRoundNodes >>= { ns =>
                  loop(ns, newFailed)
                }
              }
            }
      }

    nodeDiscovery.recentlyAlivePeersAscendingDistance
      .flatMap { peers =>
        val nodesToSyncWith = selectNodes(peers)
        loop(nodesToSyncWith, Set.empty)
      }
      .start
      .map(_.join)

  }
}
