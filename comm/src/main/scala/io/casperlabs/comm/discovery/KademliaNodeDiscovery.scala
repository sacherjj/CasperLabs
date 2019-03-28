package io.casperlabs.comm.discovery

import scala.collection.mutable
import scala.concurrent.duration._
import cats.implicits._
import cats.temp.par._
import io.casperlabs.catscontrib._
import Catscontrib._
import cats.effect._
import io.casperlabs.comm._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import monix.eval.{TaskLift, TaskLike}
import monix.execution.Scheduler

object KademliaNodeDiscovery {
  def create[F[_]: Concurrent: Log: Time: Metrics: TaskLike: TaskLift: PeerNodeAsk: Timer: Par](
      id: NodeIdentifier,
      port: Int,
      timeout: FiniteDuration
  )(
      init: Option[PeerNode]
  )(implicit scheduler: Scheduler): Resource[F, NodeDiscovery[F]] = {
    val kademliaRpcResource = Resource.make(CachedConnections[F, KademliaConnTag].map {
      implicit cache =>
        new GrpcKademliaRPC(port, timeout)
    })(
      kRpc =>
        Concurrent[F]
          .attempt(kRpc.shutdown())
          .flatMap(
            _.fold(ex => Log[F].error("Failed to properly shutdown KademliaRPC", ex), _.pure[F])
          )
    )
    kademliaRpcResource.flatMap { implicit kRpc =>
      Resource.liftF(for {
        table <- PeerTable[F](id)
        knd   = new KademliaNodeDiscovery[F](id, timeout, table)
        _     <- init.fold(().pure[F])(knd.addNode)
      } yield knd)
    }
  }
}

private[discovery] class KademliaNodeDiscovery[F[_]: Sync: Log: Time: Metrics: KademliaRPC: Par](
    id: NodeIdentifier,
    timeout: FiniteDuration,
    table: PeerTable[F],
    alpha: Int = 3,
    k: Int = PeerTable.Redundancy
) extends NodeDiscovery[F] {
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "discovery.kademlia")

  // TODO inline usage
  private[discovery] def addNode(peer: PeerNode): F[Unit] =
    for {
      _     <- table.updateLastSeen(peer)
      peers <- table.peers
      _     <- Metrics[F].setGauge("peers", peers.length.toLong)
    } yield ()

  private def pingHandler(peer: PeerNode): F[Unit] =
    addNode(peer) *> Metrics[F].incrementCounter("handle.ping")

  private def lookupHandler(peer: PeerNode, id: NodeIdentifier): F[Seq[PeerNode]] =
    for {
      peers <- table.lookup(id)
      _     <- Metrics[F].incrementCounter("handle.lookup")
      _     <- addNode(peer)
    } yield peers

  def discover: F[Unit] = {

    val initRPC = KademliaRPC[F].receive(pingHandler, lookupHandler)

    val findNew = for {
      _ <- Time[F].sleep(9.seconds)
      _ <- findMorePeers()
    } yield ()

    initRPC *> findNew.forever
  }

  private def findMorePeers(
      alpha: Int = 3,
      k: Int = PeerTable.Redundancy,
      bucketsToFill: Int = 5
  ): F[Unit] = {

    def generateId(distance: Int): NodeIdentifier = {
      val target       = id.key.to[mutable.ArrayBuffer] // Our key
      val byteIndex    = distance / 8
      val differentBit = 1 << (distance % 8)
      target(byteIndex) = (target(byteIndex) ^ differentBit).toByte // A key at a distance dist from me
      NodeIdentifier(target)
    }

    for {
      targetIds <- table.sparseness.map(_.take(bucketsToFill).map(generateId).toList)
      _         <- targetIds.traverse(lookup)
    } yield ()
  }

  def lookup(id: NodeIdentifier): F[Option[PeerNode]] = {
    def loop(
        successQueriesN: Int,
        alreadyQueried: Set[PeerNode],
        shortlist: Seq[PeerNode],
        maybeClosestPeerNode: Option[PeerNode]
    ): F[Option[PeerNode]] =
      if (shortlist.isEmpty || successQueriesN >= k) {
        maybeClosestPeerNode.pure[F]
      } else {
        val (callees, rest) = shortlist.toList.splitAt(alpha)
        for {
          responses <- callees.parTraverse { callee =>
                        for {
                          maybeNodes <- KademliaRPC[F].lookup(id, callee)
                          _          <- maybeNodes.fold(().pure[F])(_ => addNode(callee))
                        } yield (callee, maybeNodes)
                      }
          newAlreadyQueried = alreadyQueried ++ responses.collect {
            case (callee, Some(_)) => callee
          }.toSet
          returnedPeers      = responses.flatMap(_._2.toList.flatten)
          newShortlist       = rest ::: returnedPeers.filterNot(newAlreadyQueried)
          newClosestPeerNode = returnedPeers.minBy(p => PeerTable.longestCommonBitPrefix(id, p.id))
          newSuccessQueriesN = successQueriesN + responses.count(_._2.nonEmpty)
          res <- maybeClosestPeerNode.fold(
                  loop(
                    newSuccessQueriesN,
                    newAlreadyQueried,
                    newShortlist,
                    newClosestPeerNode.some
                  )
                ) { closestId =>
                  if (PeerTable.xorDistance(id, newClosestPeerNode.id) <
                        PeerTable.xorDistance(id, closestId.id)) {
                    loop(
                      newSuccessQueriesN,
                      newAlreadyQueried,
                      newShortlist,
                      newClosestPeerNode.some
                    )
                  } else {
                    closestId.some.pure[F]
                  }
                }
        } yield res

      }

    for {
      shortlist   <- table.lookup(id).map(_.take(alpha))
      closestNode <- loop(0, Set.empty[PeerNode], shortlist, None)
    } yield closestNode
  }

  override def peers: F[Seq[PeerNode]] = table.peers
}
