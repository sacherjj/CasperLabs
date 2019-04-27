package io.casperlabs.comm.gossiping

import cats.effect._
import cats.implicits._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.Utils._
import io.casperlabs.shared.Log
import simulacrum.typeclass

import scala.util.Random

@typeclass
trait Relaying[F[_]] {
  def relay(hashes: List[ByteString]): F[Unit]
}

object RelayingImpl {
  def apply[F[_]: Sync: Par: Log: NodeAsk](
      nd: NodeDiscovery[F],
      connectToGossip: GossipService.Connector[F],
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
class RelayingImpl[F[_]: Sync: Par: Log: NodeAsk](
    nd: NodeDiscovery[F],
    connectToGossip: Node => F[GossipService[F]],
    relayFactor: Int,
    maxToTry: Int
) extends Relaying[F] {

  override def relay(hashes: List[ByteString]): F[Unit] = {
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

    for {
      peers <- nd.alivePeersAscendingDistance
      _     <- hashes.parTraverse(hash => loop(hash, Random.shuffle(peers), 0, 0))
    } yield ()
  }

  private def relay(peer: Node, hash: ByteString): F[Boolean] =
    (for {
      service  <- connectToGossip(peer)
      local    <- NodeAsk[F].ask
      response <- service.newBlocks(NewBlocksRequest(sender = local.some, blockHashes = List(hash)))
      msg = if (response.isNew)
        s"${peer.show} accepted block for downloading ${hex(hash)}"
      else
        s"${peer.show} rejected block ${hex(hash)}"
      _ <- Log[F].debug(msg)
    } yield response.isNew).handleErrorWith { e =>
      for {
        _ <- Log[F].debug(s"NewBlocks request failed ${peer.show}, $e")
      } yield false
    }
}
