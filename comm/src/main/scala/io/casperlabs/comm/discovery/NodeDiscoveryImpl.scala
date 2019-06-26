package io.casperlabs.comm.discovery

import scala.collection.mutable
import scala.concurrent.duration._
import cats.implicits._
import cats.effect.implicits._
import cats.temp.par._
import io.casperlabs.catscontrib._
import Catscontrib._
import cats.Monad
import cats.effect._
import cats.effect.concurrent.Ref
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.NodeDiscoveryImpl.Millis
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import monix.eval.{TaskLift, TaskLike}
import monix.execution.Scheduler

import scala.util.Random

object NodeDiscoveryImpl {
  type Millis = Long

  def create[F[_]: Concurrent: Log: Metrics: TaskLike: TaskLift: NodeAsk: Timer: Par](
      id: NodeIdentifier,
      port: Int,
      timeout: FiniteDuration,
      gossipingEnabled: Boolean,
      gossipingRelayFactor: Int,
      gossipingRelaySaturation: Int
  )(
      init: Option[Node]
  )(implicit scheduler: Scheduler): Resource[F, NodeDiscovery[F]] = {

    def makeKademliaRpc: Resource[F, GrpcKademliaService[F]] =
      Resource.make(
        CachedConnections[
          F,
          KademliaConnTag
        ].map { implicit cache =>
          new GrpcKademliaService(
            port,
            timeout
          )
        }
      )(
        kRpc =>
          Concurrent[F]
            .attempt(kRpc.shutdown())
            .flatMap(
              _.fold(
                ex =>
                  Log[F].error(
                    "Failed to properly shutdown KademliaRPC",
                    ex
                  ),
                _.pure[F]
              )
            )
      )

    def makeNodeDiscoveryImpl(
        implicit K: GrpcKademliaService[F]
    ): Resource[F, NodeDiscoveryImpl[F]] =
      Resource.liftF(for {
        table              <- PeerTable[F](id)
        recentlyAlivePeers <- Ref.of[F, (Set[Node], Millis)]((Set.empty, 0L))
        nodeDiscovery <- Sync[F].delay {
                          val alivePeersCacheSize =
                            if (gossipingRelaySaturation == 100) {
                              Int.MaxValue
                            } else {
                              (gossipingRelayFactor * 100) / (100 - gossipingRelaySaturation)
                            }
                          new NodeDiscoveryImpl[F](
                            id = id,
                            table = table,
                            recentlyAlivePeersRef = recentlyAlivePeers,
                            gossipingEnabled = gossipingEnabled,
                            alivePeersCacheSize = alivePeersCacheSize
                          )
                        }
        _ <- init.fold(().pure[F])(nodeDiscovery.addNode)
      } yield nodeDiscovery)

    def scheduleRecentlyAlivePeersCacheUpdate(implicit N: NodeDiscoveryImpl[F]): Resource[F, Unit] =
      Resource
        .make(
          N.schedulePeriodicRecentlyAlivePeersCacheUpdate.start
        )(_.cancel.void)
        .void

    for {
      implicit0(kademliaRpcResource: GrpcKademliaService[F]) <- makeKademliaRpc
      implicit0(nodeDiscovery: NodeDiscoveryImpl[F])         <- makeNodeDiscoveryImpl
      _                                                      <- scheduleRecentlyAlivePeersCacheUpdate
    } yield nodeDiscovery: NodeDiscovery[F]
  }
}

private[discovery] class NodeDiscoveryImpl[F[_]: Monad: Log: Timer: Metrics: KademliaService: Par](
    id: NodeIdentifier,
    val table: PeerTable[F],
    recentlyAlivePeersRef: Ref[F, (Set[Node], Millis)],
    alpha: Int = 3,
    k: Int = PeerTable.Redundancy,
    gossipingEnabled: Boolean,
    /** Actual size can be greater due to batched parallel pings, but not less.
      * Stops early without pinging all peers if reached required size. */
    alivePeersCacheSize: Int = 20,
    /* Threshold to start gradually pinging peers to fill cache */
    alivePeersCacheMinThreshold: Int = 10,
    /* Period to re-fill cache */
    alivePeersCacheExpirationPeriod: FiniteDuration = 5.minutes,
    /* Period to update the cache */
    alivePeersCacheUpdatePeriod: FiniteDuration = 15.seconds,
    /* Batches pinged in parallel */
    alivePeersCachePingsBatchSize: Int = 10
) extends NodeDiscovery[F] {
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "discovery.kademlia")

  // TODO inline usage
  private[discovery] def addNode(peer: Node): F[Unit] =
    for {
      _     <- table.updateLastSeen(peer)
      peers <- table.peersAscendingDistance
      _     <- Metrics[F].setGauge("peers", peers.length.toLong)
    } yield ()

  private def pingHandler(peer: Node): F[Unit] =
    addNode(peer) *> Metrics[F].incrementCounter("handle.ping")

  private def lookupHandler(peer: Node, id: NodeIdentifier): F[Seq[Node]] =
    for {
      peers <- table.lookup(id)
      _     <- Metrics[F].incrementCounter("handle.lookup")
      _     <- addNode(peer)
    } yield peers

  def discover: F[Unit] = {

    val initRPC = KademliaService[F].receive(pingHandler, lookupHandler)

    val findNew = for {
      _ <- Timer[F].sleep(9.seconds)
      _ <- findMorePeers()
    } yield ()

    initRPC *> lookup(id) *> findNew.forever
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
      NodeIdentifier(target.toList)
    }

    for {
      targetIds <- table.sparseness.map(_.take(bucketsToFill).map(generateId).toList)
      _         <- targetIds.traverse(lookup)
    } yield ()
  }

  def lookup(toLookup: NodeIdentifier): F[Option[Node]] = {
    def loop(successQueriesN: Int, alreadyQueried: Set[NodeIdentifier], shortlist: Seq[Node])(
        maybeClosestPeerNode: Option[Node]
    ): F[Option[Node]] =
      if (shortlist.isEmpty || successQueriesN >= k) {
        maybeClosestPeerNode.pure[F]
      } else {
        val (callees, rest) = shortlist.toList.splitAt(alpha)
        for {
          responses <- callees.parTraverse { callee =>
                        for {
                          maybeNodes <- KademliaService[F].lookup(toLookup, callee)
                          _          <- maybeNodes.fold(().pure[F])(_ => addNode(callee))
                        } yield (callee, maybeNodes)
                      }
          newAlreadyQueried = alreadyQueried ++ responses.collect {
            case (callee, Some(_)) => NodeIdentifier(callee.id)
          }.toSet
          returnedPeers = responses.flatMap(_._2.toList.flatten).distinct
          recursion = loop(
            successQueriesN + responses.count(_._2.nonEmpty),
            newAlreadyQueried,
            rest ::: returnedPeers.filterNot(p => newAlreadyQueried(NodeIdentifier(p.id)))
          ) _
          maybeNewClosestPeerNode = if (returnedPeers.nonEmpty)
            returnedPeers.minBy(p => PeerTable.xorDistance(toLookup.asByteString, p.id)).some
          else None
          res <- (maybeNewClosestPeerNode, maybeClosestPeerNode) match {
                  case (x @ Some(_), None) => recursion(x)
                  case (x @ Some(newClosestPeerNode), Some(closestPeerNode))
                      if PeerTable.xorDistance(toLookup.asByteString, newClosestPeerNode.id) <
                        PeerTable.xorDistance(toLookup.asByteString, closestPeerNode.id) =>
                    recursion(x)
                  case _ => maybeClosestPeerNode.pure[F]
                }
        } yield res
      }

    for {
      shortlist <- table.lookup(toLookup).map(_.take(alpha))
      closestNode <- shortlist.headOption
                      .filter(p => NodeIdentifier(p.id) == toLookup)
                      .fold(loop(0, Set(id), shortlist)(None))(_.some.pure[F])

    } yield closestNode
  }

  override def recentlyAlivePeersAscendingDistance: F[List[Node]] =
    if (gossipingEnabled)
      recentlyAlivePeersRef.get.map {
        case (recentlyAlivePeers, _) => PeerTable.sort(recentlyAlivePeers.toList, id)(_.id)
      } else
      // TODO: this is misleading because returned peers won't necessarily be alive.
      // Though, this is fine because it's only used by
      // the old legacy communication layer code which should go away eventually
      // io.casperlabs.comm.rp.Connect#findAndConnect
      // The old code maintains its own list of alive connections
      table.peersAscendingDistance

  def schedulePeriodicRecentlyAlivePeersCacheUpdate: F[Unit] =
    updateRecentlyAlivePeers >>
      Timer[F].sleep(alivePeersCacheUpdatePeriod) >>
      schedulePeriodicRecentlyAlivePeersCacheUpdate

  // TODO: The logic might be too complex here
  // Possible simplification would be pinging all known peers in some period and cache responded ones
  def updateRecentlyAlivePeers: F[Unit] =
    for {
      (recentlyAlivePeers, lastTimeAccess) <- recentlyAlivePeersRef.get
      currentTime                          <- Timer[F].clock.realTime(MILLISECONDS)
      oldEnough                            = FiniteDuration(currentTime - lastTimeAccess, MILLISECONDS) > alivePeersCacheExpirationPeriod
      alivePeers                           <- filterAlive(recentlyAlivePeers.toList)
      tooFewAlivePeers                     = alivePeers.size < alivePeersCacheMinThreshold
      newAlivePeers <- if (oldEnough || tooFewAlivePeers)
                        for {
                          allKnownPeers <- table.peersAscendingDistance.map(Random.shuffle(_))
                          notPingedYet = if (oldEnough) allKnownPeers
                          else allKnownPeers.filterNot(recentlyAlivePeers)
                          newAlivePeers <- filterAlive(notPingedYet, alivePeersCacheSize)
                        } yield if (oldEnough) newAlivePeers else newAlivePeers ++ alivePeers
                      else
                        alivePeers.pure[F]
      _ <- recentlyAlivePeersRef.set(
            (newAlivePeers.toSet, if (oldEnough) currentTime else lastTimeAccess)
          )
    } yield ()

  def filterAlive(peers: List[Node]): F[List[Node]] =
    peers.parFlatTraverse { peer =>
      KademliaService[F].ping(peer).map(success => if (success) List(peer) else Nil)
    }

  def filterAlive(peers: List[Node], max: Int): F[List[Node]] = {
    val batches = peers.grouped(alivePeersCachePingsBatchSize).toList
    batches.foldLeftM(List.empty[Node]) {
      case (acc, _) if acc.size >= max   => acc.pure[F]
      case (acc, batch) if batch.isEmpty => acc.pure[F]
      case (acc, batch)                  => filterAlive(batch).map(acc ++ _)
    }
  }
}
