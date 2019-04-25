package io.casperlabs.comm.gossiping

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import cats.temp.par._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.shared.Log

import scala.concurrent.duration.FiniteDuration

trait InitialSynchronization[F[_]] {

  /**
    * Synchronises the node on startup with the last tips
    * @return Handle which will be resolved when node is fully synced
    */
  def syncOnStartup(roundPeriod: FiniteDuration): F[F[Unit]]
}

class InitialSynchronizationImpl[F[_]: Concurrent: Par: Log: Timer](
    nd: NodeDiscovery[F],
    self: GossipServiceServer[F],
    bootstrap: InitialSynchronizationImpl.Bootstrap,
    // Filter function to select nodes (with memoization) to synchronize with
    // Second arg is a list of all known peer nodes excluding bootstrap
    selectNodes: (InitialSynchronizationImpl.Bootstrap, List[Node]) => List[Node],
    connect: Node => GossipService[F]
) extends InitialSynchronization[F] {
  override def syncOnStartup(roundPeriod: FiniteDuration): F[F[Unit]] = {
    /* True if tips were new and we need to retry a loop */
    def sync(node: Node): F[Boolean] = {
      val service = connect(node)
      val retrieveTips =
        service.streamDagTipBlockSummaries(StreamDagTipBlockSummariesRequest()).toListL
      retrieveTips >>= { tips =>
        self
          .newBlocksSynchronous(
            NewBlocksRequest(
              sender = node.some,
              blockHashes = tips.map(_.blockHash)
            )
          )
          .map(_.isNew)
      }
    }

    def loop(nodes: List[Node]): F[Unit] =
      nodes
        .traverse(sync)
        .map(_.exists(identity))
        .ifM(Timer[F].sleep(roundPeriod) >> loop(nodes), ().pure[F])

    nd.alivePeersAscendingDistance
      .flatMap { peers =>
        val nodesToSyncWith = selectNodes(bootstrap, peers.filterNot(_ == bootstrap))
        loop(nodesToSyncWith)
      }
      .start
      .map(_.join)
  }
}

object InitialSynchronizationImpl {
  import shapeless.tag.@@
  sealed trait BootstrapTag
  type Bootstrap = Node @@ BootstrapTag

  def Bootstrap(node: Node): Bootstrap = node.asInstanceOf[Bootstrap]
}
