package io.casperlabs.comm.gossiping

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import cats.temp.par._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.InitialSynchronizationImpl.SynchronizationError
import io.casperlabs.shared.Log

import scala.concurrent.duration.FiniteDuration

trait InitialSynchronization[F[_]] {

  /**
    * Synchronizes the node with the last tips.
    *
    * @return Handle which will be resolved when node is considered to be fully synced
    */
  def sync(): F[F[Unit]]
}

/**
  * Synchronizes the node with peers in rounds until [[minSuccessful]] nodes
  * respond successfully in some round.
  *
  * @param selectNodes   Filtering function to select nodes to synchronize with in a round.
  *                      Second arg is a list of all known peers excluding bootstrap
  * @param memoizeNodes  If true applies [[selectNodes]] function only once and stores results for next rounds
  *                      Otherwise, invokes it each round
  * @param minSuccessful Minimal number of successful responses in a round to consider synchronisation as successful
  * @param roundPeriod   Delay between synchronisation rounds
  */
class InitialSynchronizationImpl[F[_]: Concurrent: Par: Log: Timer](
    nodeDiscovery: NodeDiscovery[F],
    selfGossipService: GossipServiceServer[F],
    bootstrap: InitialSynchronizationImpl.Bootstrap,
    selectNodes: (InitialSynchronizationImpl.Bootstrap, List[Node]) => List[Node],
    memoizeNodes: Boolean,
    connector: GossipService.Connector[F],
    minSuccessful: Int Refined Positive,
    skipFailedNodesInNextRounds: Boolean,
    roundPeriod: FiniteDuration
) extends InitialSynchronization[F] {
  override def sync(): F[F[Unit]] = {
    def sync(node: Node): F[Unit] =
      for {
        service <- connector(node)
        _       <- Log[F].debug(s"Syncing with a node: ${node.show}")
        tips    <- service.streamDagTipBlockSummaries(StreamDagTipBlockSummariesRequest()).toListL
        _ <- selfGossipService.newBlocksSynchronous(
              NewBlocksRequest(
                sender = node.some,
                blockHashes = tips.map(_.blockHash)
              ),
              skipRelaying = true
            )
      } yield ()

    def loop(nodes: List[Node], failed: Set[Node]): F[Unit] =
      if (nodes.isEmpty) {
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
                    nodeDiscovery.alivePeersAscendingDistance.map { peers =>
                      val nodes = selectNodes(bootstrap, peers.filterNot(_ == bootstrap))
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

    nodeDiscovery.alivePeersAscendingDistance
      .flatMap { peers =>
        val nodesToSyncWith = selectNodes(bootstrap, peers.filterNot(_ == bootstrap))
        loop(nodesToSyncWith, Set.empty)
      }
      .start
      .map(_.join)

  }
}

object InitialSynchronizationImpl {

  import shapeless.tag.@@
  import shapeless.tag

  sealed trait BootstrapTag

  type Bootstrap = Node @@ BootstrapTag

  final case class SynchronizationError() extends Exception

  def Bootstrap(node: Node): Bootstrap = node.asInstanceOf[Bootstrap]
}
