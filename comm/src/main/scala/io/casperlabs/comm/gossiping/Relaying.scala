package io.casperlabs.comm.gossiping

import cats.effect._
import cats.implicits._
import cats.temp.par._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import simulacrum.typeclass

import scala.util.Random

@typeclass
trait Relaying[F[_]] {
  def relay(summary: BlockSummary): F[Unit]
}

object RelayingImpl {
  def apply[F[_]: Sync: Par: NodeAsk](
      nd: NodeDiscovery[F],
      connectToGossip: Node => F[GossipService[F]],
      relayFactor: Int,
      relaySaturation: Int
  ): Relaying[F] = {
    val maxToTry = if (relaySaturation == 100) {
      Int.MaxValue
    } else {
      (relayFactor * 100) / (100 - relaySaturation)
    }
    new RelayingImpl[F](nd, connectToGossip, relayFactor, maxToTry)
  }
}

/**
  * https://techspec.casperlabs.io/technical-details/global-state/communications#picking-nodes-for-gossip
  */
class RelayingImpl[F[_]: Sync: Par: NodeAsk](
    nd: NodeDiscovery[F],
    connectToGossip: Node => F[GossipService[F]],
    relayFactor: Int,
    maxToTry: Int
) extends Relaying[F] {

  override def relay(summary: BlockSummary): F[Unit] = {
    def loop(peers: List[Node], contacted: Int): F[Unit] = {
      val parallelism = math.min(relayFactor, maxToTry - contacted)
      if (parallelism > 0 && peers.nonEmpty) {
        val (recipients, rest) = Random.shuffle(peers).splitAt(parallelism)
        for {
          results <- recipients.parTraverse(relay(_, summary))
          _ <- if (results.exists(!_))
                loop(rest, contacted + recipients.size)
              else
                ().pure[F]
        } yield ()
      } else {
        ().pure[F]
      }
    }

    for {
      peers <- nd.alivePeersAscendingDistance
      _     <- loop(peers, 0)
    } yield ()
  }

  private def relay(peer: Node, summary: BlockSummary): F[Boolean] =
    (for {
      service <- connectToGossip(peer)
      local   <- NodeAsk[F].ask
      response <- service.newBlocks(
                   NewBlocksRequest(sender = local.some, blockHashes = List(summary.blockHash)))
    } yield response.isNew).handleError(_ => false)
}
