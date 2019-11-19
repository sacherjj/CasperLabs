package io.casperlabs.comm.discovery

import scala.collection.mutable
import scala.concurrent.duration._
import cats._
import cats.implicits._
import cats.effect.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import com.google.common.cache.{Cache, CacheBuilder}
import io.casperlabs.catscontrib._
import Catscontrib._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.NodeDiscoveryImpl.Millis
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import java.util.concurrent.TimeUnit

import com.google.protobuf.ByteString
import monix.eval.{TaskLift, TaskLike}
import monix.execution.Scheduler

import scala.util.Random

object NodeDiscoveryImpl {
  type Millis = Long

  def create[F[_]: Concurrent: Log: Metrics: TaskLike: TaskLift: NodeAsk: BootstrapsAsk: Timer: Parallel](
      id: NodeIdentifier,
      port: Int,
      timeout: FiniteDuration,
      gossipingRelayFactor: Int,
      gossipingRelaySaturation: Int,
      ingressScheduler: Scheduler,
      egressScheduler: Scheduler,
      /* Threshold to start gradually pinging peers to fill cache */
      alivePeersCacheMinThreshold: Int = 10,
      /* Period to re-fill cache */
      alivePeersCacheExpirationPeriod: FiniteDuration = 5.minutes,
      /* Period to update the cache */
      alivePeersCacheUpdatePeriod: FiniteDuration = 15.seconds,
      /* Batches pinged in parallel */
      alivePeersCachePingsBatchSize: Int = 10
  ): Resource[F, NodeDiscovery[F]] = {

    def makeKademliaRpc: Resource[F, GrpcKademliaService[F]] =
      Resource.make(
        CachedConnections[
          F,
          KademliaConnTag
        ].map { implicit cache =>
          new GrpcKademliaService(
            port,
            timeout,
            ingressScheduler,
            egressScheduler
          )
        }
      )(
        kRpc =>
          Concurrent[F]
            .attempt(kRpc.shutdown())
            .flatMap(
              _.fold(
                ex => Log[F].error(s"Failed to properly shutdown KademliaRPC: $ex"),
                _.pure[F]
              )
            )
      )

    def makeNodeDiscoveryImpl(
        implicit K: GrpcKademliaService[F]
    ): Resource[F, NodeDiscoveryImpl[F]] =
      Resource.liftF(for {
        table              <- PeerTable[F](id)
        chainId            <- NodeAsk[F].ask.map(_.chainId)
        recentlyAlivePeers <- Ref.of[F, (Set[Node], Millis)]((Set.empty, 0L))
        temporaryBans      <- NodeCache(alivePeersCacheExpirationPeriod)
        nodeDiscovery <- Sync[F].delay {
                          val alivePeersCacheSize =
                            if (gossipingRelaySaturation == 100) {
                              Int.MaxValue
                            } else {
                              (gossipingRelayFactor * 100) / (100 - gossipingRelaySaturation)
                            }
                          new NodeDiscoveryImpl[F](
                            id = id,
                            chainId = chainId,
                            table = table,
                            recentlyAlivePeersRef = recentlyAlivePeers,
                            temporaryBans = temporaryBans,
                            alivePeersCacheSize = alivePeersCacheSize,
                            alivePeersCacheMinThreshold = alivePeersCacheMinThreshold,
                            alivePeersCacheExpirationPeriod = alivePeersCacheExpirationPeriod,
                            alivePeersCacheUpdatePeriod = alivePeersCacheUpdatePeriod,
                            alivePeersCachePingsBatchSize = alivePeersCachePingsBatchSize
                          )
                        }
        init <- BootstrapsAsk[F].ask
        _    <- init.traverse(nodeDiscovery.addNode)
      } yield nodeDiscovery)

    def scheduleRecentlyAlivePeersCacheUpdate(implicit N: NodeDiscoveryImpl[F]): Resource[F, Unit] =
      Resource
        .make(
          N.schedulePeriodicRecentlyAlivePeersCacheUpdate.start
        )(_.cancel.void)
        .void

    def initializeMetrics: Resource[F, Unit] = {
      val discoverySource = Metrics.Source(CommMetricsSource, "discovery.kademlia")
      val kademliaSource  = Metrics.Source(CommMetricsSource, "discovery.kademlia.grpc")
      Resource.liftF(for {
        _ <- Metrics[F].incrementCounter("handle.ping", 0)(discoverySource)
        _ <- Metrics[F].incrementCounter("handle.lookup", 0)(discoverySource)
        _ <- Metrics[F].incrementCounter("ping", 0)(kademliaSource)
        _ <- Metrics[F].incrementCounter("protocol-lookup-send", 0)(kademliaSource)
      } yield ())
    }

    for {
      implicit0(kademliaRpcResource: GrpcKademliaService[F]) <- makeKademliaRpc
      implicit0(nodeDiscovery: NodeDiscoveryImpl[F])         <- makeNodeDiscoveryImpl
      _                                                      <- scheduleRecentlyAlivePeersCacheUpdate
      _                                                      <- initializeMetrics
    } yield nodeDiscovery: NodeDiscovery[F]
  }

  /** Cache nodes temporarily. */
  private[discovery] class NodeCache[F[_]: Sync](cache: Cache[Node, NodeCache.Marker]) {
    def contains(node: Node): F[Boolean] =
      Sync[F].delay(cache.getIfPresent(node) != null)
    def add(node: Node): F[Unit] =
      Sync[F].delay(cache.put(node, NodeCache.Marker))
  }

  private[discovery] object NodeCache {
    private object Marker
    private type Marker = Marker.type

    def apply[F[_]: Sync](duration: FiniteDuration): F[NodeCache[F]] = Sync[F].delay {
      val cache = CacheBuilder
        .newBuilder()
        .expireAfterWrite(
          duration.toMillis,
          TimeUnit.MILLISECONDS
        )
        .build[Node, Marker]()

      new NodeCache(cache)
    }
  }

}

private[discovery] class NodeDiscoveryImpl[F[_]: MonadThrowable: Log: Timer: Metrics: KademliaService: Parallel](
    id: NodeIdentifier,
    chainId: ByteString,
    val table: PeerTable[F],
    recentlyAlivePeersRef: Ref[F, (Set[Node], Millis)],
    temporaryBans: NodeDiscoveryImpl.NodeCache[F],
    alpha: Int = 3,
    k: Int = PeerTable.Redundancy,
    /** Actual size can be greater due to batched parallel pings, but not less.
      * Stops early without pinging all peers if reached required size. */
    alivePeersCacheSize: Int,
    /* Threshold to start gradually pinging peers to fill cache */
    alivePeersCacheMinThreshold: Int,
    /* Period to re-fill cache */
    alivePeersCacheExpirationPeriod: FiniteDuration,
    /* Period to update the cache */
    alivePeersCacheUpdatePeriod: FiniteDuration,
    /* Batches pinged in parallel */
    alivePeersCachePingsBatchSize: Int
) extends NodeDiscovery[F] {
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "discovery.kademlia")

  // TODO inline usage
  private[discovery] def addNode(peer: Node): F[Unit] =
    for {
      _     <- table.updateLastSeen(peer)
      peers <- table.peersAscendingDistance
      _     <- Metrics[F].setGauge("peers_all_known", peers.length.toLong)
    } yield ()

  private def verifyChain(peer: Node, handlerName: String): F[Unit] =
    (Metrics[F].incrementCounter(s"handle.$handlerName.wrong_chain") >>
      MonadThrowable[F].raiseError[Unit](
        new IllegalArgumentException(
          s"Wrong chain id, expected: $chainId, received: ${peer.chainId}"
        )
      )).whenA(peer.chainId != chainId)

  private def pingHandler(peer: Node): F[Unit] =
    verifyChain(peer, "ping") >> addNode(peer) >> Metrics[F].incrementCounter("handle.ping")

  private def lookupHandler(peer: Node, id: NodeIdentifier): F[Seq[Node]] =
    for {
      _     <- verifyChain(peer, "lookup")
      peers <- table.lookup(id)
      _     <- Metrics[F].incrementCounter("handle.lookup")
      _     <- addNode(peer)
    } yield peers

  override def discover: F[Unit] = {

    val initRPC = KademliaService[F].receive(pingHandler, lookupHandler)

    val findNew = for {
      _ <- Timer[F].sleep(9.seconds)
      _ <- findMorePeers(5)
    } yield ()

    initRPC *> lookup(id) *> findNew.forever
  }

  private def findMorePeers(bucketsToFill: Int): F[Unit] = {

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

  override def lookup(toLookup: NodeIdentifier): F[Option[Node]] = {
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
            case (callee, _) => NodeIdentifier(callee.id)
          }.toSet
          returnedPeers = responses.flatMap(_._2.toList.flatten).distinct
          newShortList = rest ::: returnedPeers.filterNot(
            p => newAlreadyQueried(NodeIdentifier(p.id))
          )
          recursion = loop(
            successQueriesN + responses.count(_._2.nonEmpty),
            newAlreadyQueried,
            newShortList
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
                  case _ =>
                    maybeClosestPeerNode.pure[F]
                }
        } yield res
      }

    for {
      shortlist <- recentlyAlivePeersAscendingDistance(toLookup).map(_.take(alpha))
      closestNode <- shortlist.headOption
                      .filter(p => NodeIdentifier(p.id) == toLookup)
                      .fold(loop(0, Set(id), shortlist)(None))(_.some.pure[F])

    } yield closestNode
  }

  override def recentlyAlivePeersAscendingDistance: F[List[Node]] =
    recentlyAlivePeersAscendingDistance(id)

  private def recentlyAlivePeersAscendingDistance(anchorId: NodeIdentifier): F[List[Node]] =
    for {
      peers <- recentlyAlivePeersRef.get.map {
                case (recentlyAlivePeers, _) =>
                  PeerTable.sort(recentlyAlivePeers.toList, anchorId)(_.id)
              }
      notBanned <- peers.filterA(temporaryBans.contains(_).map(!_))
    } yield notBanned

  private[discovery] def schedulePeriodicRecentlyAlivePeersCacheUpdate: F[Unit] =
    updateRecentlyAlivePeers >>
      Timer[F].sleep(alivePeersCacheUpdatePeriod) >>
      schedulePeriodicRecentlyAlivePeersCacheUpdate

  // TODO: The logic might be too complex here
  // Possible simplification would be pinging all known peers in some period and cache responded ones
  private[discovery] def updateRecentlyAlivePeers: F[Unit] =
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
      _ <- Metrics[F].setGauge("peers_alive", newAlivePeers.size.toLong)
    } yield ()

  private def filterAlive(peers: List[Node]): F[List[Node]] =
    peers.parFlatTraverse { peer =>
      KademliaService[F].ping(peer).map(success => if (success) List(peer) else Nil)
    }

  private def filterAlive(peers: List[Node], max: Int): F[List[Node]] = {
    val batches = peers.grouped(alivePeersCachePingsBatchSize).toList
    batches.foldLeftM(List.empty[Node]) {
      case (acc, _) if acc.size >= max   => acc.pure[F]
      case (acc, batch) if batch.isEmpty => acc.pure[F]
      case (acc, batch)                  => filterAlive(batch).map(acc ++ _)
    }
  }

  override def banTemp(node: Node): F[Unit] =
    temporaryBans.add(node)
}
