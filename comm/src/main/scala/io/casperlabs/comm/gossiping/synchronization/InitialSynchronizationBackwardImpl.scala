package io.casperlabs.comm.gossiping.synchronization

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.synchronization.InitialSynchronization.SynchronizationError
import io.casperlabs.shared.Log

import scala.concurrent.duration.FiniteDuration

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
class InitialSynchronizationBackwardImpl[F[_]: Concurrent: Log: Timer](
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
        _       <- Log[F].debug(s"Syncing with a node: ${node.show -> "peer"}")
        lms     <- service.streamLatestMessages(StreamLatestMessagesRequest()).toListL
        _ <- MonadThrowable[F].rethrow {
              selfGossipService
                .newBlocksSynchronous(
                  NewBlocksRequest(
                    sender = node.some,
                    blockHashes = lms.map(_.latestBlockHash)
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
        Log[F].debug(s"Next round of syncing with nodes: ${nodes.map(_.show) -> "peers"}") >>
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
