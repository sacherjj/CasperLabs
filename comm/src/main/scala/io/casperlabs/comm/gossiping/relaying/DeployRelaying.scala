package io.casperlabs.comm.gossiping.relaying

import cats.effect._
import cats.implicits._
import cats.{Applicative, Monad, Parallel}
import com.google.protobuf.ByteString
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.{DeployGossipingMetricsSource, GossipService, NewDeploysRequest}
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import monix.execution.Scheduler
import simulacrum.typeclass

@typeclass
trait DeployRelaying[F[_]] extends Relaying[F]

class NoOpsDeployRelaying[F[_]: Applicative] extends DeployRelaying[F] {
  override def relay(hashes: List[ByteString]) = ().pure[F].pure[F]
}

object DeployRelayingImpl {
  implicit val metricsSource: Metrics.Source =
    Metrics.Source(DeployGossipingMetricsSource, "Relaying")

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("relay_accepted", 0)
      _ <- Metrics[F].incrementCounter("relay_rejected", 0)
      _ <- Metrics[F].incrementCounter("relay_failed", 0)
    } yield ()

  def apply[F[_]: ContextShift: Concurrent: Parallel: Log: Metrics: NodeAsk](
      egressScheduler: Scheduler,
      nodeDiscovery: NodeDiscovery[F],
      connectToGossip: GossipService.Connector[F],
      relayFactor: Int,
      relaySaturation: Int,
      isSynchronous: Boolean = false
  ): DeployRelaying[F] = {
    val maxToTry = if (relaySaturation == 100) {
      Int.MaxValue
    } else {
      (relayFactor * 100) / (100 - relaySaturation)
    }
    new DeployRelayingImpl[F](
      egressScheduler,
      nodeDiscovery,
      connectToGossip,
      relayFactor,
      maxToTry,
      isSynchronous
    )
  }
}

class DeployRelayingImpl[F[_]: ContextShift: Concurrent: Parallel: Log: Metrics: NodeAsk](
    egressScheduler: Scheduler,
    nodeDiscovery: NodeDiscovery[F],
    connectToGossip: Node => F[GossipService[F]],
    relayFactor: Int,
    maxToTry: Int,
    isSynchronous: Boolean
)(
    implicit val S: Metrics.Source
) extends RelayingImpl[F](
      egressScheduler,
      nodeDiscovery,
      connectToGossip,
      relayFactor,
      maxToTry,
      isSynchronous
    )
    with DeployRelaying[F] {
  override def request(
      gossipService: GossipService[F],
      local: Node,
      blockHashes: List[ByteString]
  ): F[Boolean] =
    gossipService.newDeploys(NewDeploysRequest(local.some, blockHashes)).map(_.isNew)

  override val requestName = "NewDeploys"
}
