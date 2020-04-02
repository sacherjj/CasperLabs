package io.casperlabs.comm.gossiping

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import cats.{Monad, Parallel}
import com.google.protobuf.ByteString
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.Utils._
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.shared.Log
import monix.execution.Scheduler
import simulacrum.typeclass

import scala.util.Random

@typeclass
trait Relaying[F[_]] {

  /** Notify peers about the availability of some data by hashes (blocks or deploys).
    * Return a handle that can be waited upon. */
  def relay(hashes: List[ByteString]): F[WaitHandle[F]]
}

trait BlockRelaying[F[_]] extends Relaying[F]

trait DeployRelaying[F[_]] extends Relaying[F]

trait RelayingImpl[F[_]] extends Relaying[F] {
  // Can't specify context bounds in traits
  implicit val CS: ContextShift[F]
  implicit val C: Concurrent[F]
  implicit val P: Parallel[F]
  implicit val L: Log[F]
  implicit val M: Metrics[F]
  implicit val N: NodeAsk[F]
  implicit val S: Metrics.Source

  val egressScheduler: Scheduler
  val nodeDiscovery: NodeDiscovery[F]
  val connectToGossip: Node => F[GossipService[F]]
  val relayFactor: Int
  val maxToTry: Int
  val isSynchronous: Boolean

  // Args: (remote service of a peer relaying to, local node, hashes of items to relay)
  // Returns: True  - at least one item is new to a peer
  //          False - all items already known by peer
  val request: (GossipService[F], Node, List[ByteString]) => F[Boolean]
  // For logs
  val requestName: String

  override def relay(hashes: List[ByteString]): F[WaitHandle[F]] = {
    def loop(hash: ByteString, peers: List[Node], relayed: Int, contacted: Int): F[Unit] = {
      val parallelism = math.min(relayFactor - relayed, maxToTry - contacted)
      if (parallelism > 0 && peers.nonEmpty) {
        val (recipients, rest) = peers.splitAt(parallelism)
        recipients.parTraverse(relay(_, hash)) flatMap { results =>
          loop(hash, rest, relayed + results.count(identity), contacted + recipients.size)
        }
      } else {
        ().pure[F]
      }
    }

    val run = {
      for {
        peers <- nodeDiscovery.recentlyAlivePeersAscendingDistance
        _     <- hashes.parTraverse(hash => loop(hash, Random.shuffle(peers), 0, 0))
      } yield ()
    }.timerGauge("relay")

    if (isSynchronous) {
      run *> ().pure[F].pure[F]
    } else {
      run.start.map(_.join)
    }
  }

  /** Try to relay to a peer, return whether it was new, or false if failed. */
  private def relay(peer: Node, hash: ByteString): F[Boolean] =
    (for {
      service <- connectToGossip(peer)
      local   <- NodeAsk[F].ask
      isNew <- ContextShift[F].evalOn(egressScheduler) {
                request(service, local, List(hash))
              }
      counter <- if (isNew)
                  Log[F]
                    .debug(s"${peer.show -> "peer"} accepted ${hex(hash) -> "message"}")
                    .as("relay_accepted")
                else
                  Log[F]
                    .debug(s"${peer.show -> "peer"} rejected ${hex(hash) -> "message"}")
                    .as("relay_rejected")
      _ <- Metrics[F].incrementCounter(counter)
    } yield isNew).handleErrorWith { ex =>
      for {
        _ <- Log[F].debug(s"$requestName request failed ${peer.show -> "peer"}, $ex")
        _ <- Metrics[F].incrementCounter("relay_failed")
      } yield false
    }
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
class BlockRelayingImpl[F[_]](
    val egressScheduler: Scheduler,
    val nodeDiscovery: NodeDiscovery[F],
    val connectToGossip: Node => F[GossipService[F]],
    val relayFactor: Int,
    val maxToTry: Int,
    val isSynchronous: Boolean
)(
    implicit
    override val CS: ContextShift[F],
    override val C: Concurrent[F],
    override val P: Parallel[F],
    override val L: Log[F],
    override val M: Metrics[F],
    override val N: NodeAsk[F],
    override val S: Metrics.Source
) extends BlockRelaying[F]
    with RelayingImpl[F] {
  override val request = (service, local, blockHashes) =>
    service.newBlocks(NewBlocksRequest(local.some, blockHashes)).map(_.isNew)
  override val requestName = "NewBlocks"
}
