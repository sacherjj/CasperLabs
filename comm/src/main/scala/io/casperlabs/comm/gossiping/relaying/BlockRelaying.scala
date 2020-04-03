package io.casperlabs.comm.gossiping.relaying

import cats.effect._
import cats.implicits._
import cats.{Monad, Parallel}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.{
  BlockGossipingMetricsSource,
  GossipService,
  NewBlocksRequest,
  WaitHandle
}
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.shared.Log
import monix.execution.Scheduler
import simulacrum.typeclass

@typeclass
trait BlockRelaying[F[_]] extends Relaying[F] {
  def relay(block: Block): F[WaitHandle[F]]          = relay(List(block.blockHash))
  def relay(summary: BlockSummary): F[WaitHandle[F]] = relay(List(summary.blockHash))
  def relay(blockHash: ByteString): F[WaitHandle[F]] = relay(List(blockHash))
  def relay(message: Message): F[WaitHandle[F]]      = relay(List(message.messageHash))
}

object BlockRelayingImpl {
  implicit val metricsSource: Metrics.Source =
    Metrics.Source(BlockGossipingMetricsSource, "Relaying")

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
  ): BlockRelaying[F] = {
    val maxToTry = if (relaySaturation == 100) {
      Int.MaxValue
    } else {
      (relayFactor * 100) / (100 - relaySaturation)
    }
    new BlockRelayingImpl[F](
      egressScheduler,
      nodeDiscovery,
      connectToGossip,
      relayFactor,
      maxToTry,
      isSynchronous
    )
  }
}

/**
  * https://techspec.casperlabs.io/technical-details/global-state/communications#picking-nodes-for-gossip
  */
class BlockRelayingImpl[F[_]: ContextShift: Concurrent: Parallel: Log: Metrics: NodeAsk](
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
    with BlockRelaying[F] {
  override def request(
      gossipService: GossipService[F],
      local: Node,
      blockHashes: List[ByteString]
  ): F[Boolean] =
    gossipService.newBlocks(NewBlocksRequest(local.some, blockHashes)).map(_.isNew)

  override val requestName = "NewBlocks"
}
