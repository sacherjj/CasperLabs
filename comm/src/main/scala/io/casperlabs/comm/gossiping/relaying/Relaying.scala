package io.casperlabs.comm.gossiping.relaying

import cats.Parallel
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.Utils._
import io.casperlabs.comm.gossiping.{GossipService, WaitHandle}
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.shared.Log
import monix.execution.Scheduler
import simulacrum.typeclass
import scala.util.control.NonFatal
import scala.util.Random

@typeclass
trait Relaying[F[_]] {

  /** Notify peers about the availability of some data by hashes (blocks or deploys).
    * Return a handle that can be waited upon. */
  def relay(hashes: List[ByteString]): F[WaitHandle[F]]
}

abstract class RelayingImpl[F[_]: ContextShift: Concurrent: Parallel: Log: Metrics: NodeAsk](
    val egressScheduler: Scheduler,
    val nodeDiscovery: NodeDiscovery[F],
    val connectToGossip: Node => F[GossipService[F]],
    val relayFactor: Int,
    val maxToTry: Int,
    val isSynchronous: Boolean
)(implicit val metricsSource: Metrics.Source)
    extends Relaying[F] {

  /**
    * @return 'true' if at least one item is new to a peer and 'false' if all items already known by peer
    */
  def request(gossipService: GossipService[F], local: Node, hashes: List[ByteString]): F[Boolean]
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
    } yield isNew).recoverWith {
      case NonFatal(ex) =>
        for {
          _ <- Log[F].debug(s"$requestName request failed ${peer.show -> "peer"}, $ex")
          _ <- Metrics[F].incrementCounter("relay_failed")
        } yield false
    }
}
