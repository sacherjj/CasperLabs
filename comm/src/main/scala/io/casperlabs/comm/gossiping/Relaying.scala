package io.casperlabs.comm.gossiping

import cats.{Monad, Parallel}
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.Utils._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import simulacrum.typeclass

import scala.util.Random

@typeclass
trait Relaying[F[_]] {

  /** Notify peers about the availability of some blocks.
    * Return a handle that can be waited upon. */
  def relay(hashes: List[ByteString]): F[WaitHandle[F]]
}

object RelayingImpl {
  implicit val metricsSource: Metrics.Source = Metrics.Source(GossipingMetricsSource, "Relaying")

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("relay_accepted", 0)
      _ <- Metrics[F].incrementCounter("relay_rejected", 0)
      _ <- Metrics[F].incrementCounter("relay_failed", 0)
    } yield ()

  def apply[F[_]: Concurrent: Parallel: Log: Metrics: NodeAsk](
      nd: NodeDiscovery[F],
      connectToGossip: GossipService.Connector[F],
      relayFactor: Int,
      relaySaturation: Int,
      isSynchronous: Boolean = false
  ): Relaying[F] = {
    val maxToTry = if (relaySaturation == 100) {
      Int.MaxValue
    } else {
      (relayFactor * 100) / (100 - relaySaturation)
    }
    new RelayingImpl[F](nd, connectToGossip, relayFactor, maxToTry, isSynchronous)
  }
}

/**
  * https://techspec.casperlabs.io/technical-details/global-state/communications#picking-nodes-for-gossip
  */
class RelayingImpl[F[_]: Concurrent: Parallel: Log: Metrics: NodeAsk](
    nd: NodeDiscovery[F],
    connectToGossip: Node => F[GossipService[F]],
    relayFactor: Int,
    maxToTry: Int,
    isSynchronous: Boolean
) extends Relaying[F] {
  import RelayingImpl._

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

    val run = for {
      peers <- nd.recentlyAlivePeersAscendingDistance
      _     <- hashes.parTraverse(hash => loop(hash, Random.shuffle(peers), 0, 0))
    } yield ()

    if (isSynchronous) {
      run *> ().pure[F].pure[F]
    } else {
      run.start.map(_.join)
    }
  }

  /** Try to relay to a peer, return whether it was new, or false if failed. */
  private def relay(peer: Node, hash: ByteString): F[Boolean] =
    (for {
      service  <- connectToGossip(peer)
      local    <- NodeAsk[F].ask
      response <- service.newBlocks(NewBlocksRequest(sender = local.some, blockHashes = List(hash)))
      counter <- if (response.isNew)
                  Log[F]
                    .debug(s"${peer.show -> "peer"} accepted ${hex(hash) -> "block"}")
                    .as("relay_accepted")
                else
                  Log[F]
                    .debug(s"${peer.show -> "peer"} rejected ${hex(hash) -> "block"}")
                    .as("relay_rejected")
      _ <- Metrics[F].incrementCounter(counter)
    } yield response.isNew).handleErrorWith { ex =>
      for {
        _ <- Log[F].debug(s"NewBlocks request failed ${peer.show -> "peer"}, $ex")
        _ <- Metrics[F].incrementCounter("relay_failed")
      } yield false
    }
}
