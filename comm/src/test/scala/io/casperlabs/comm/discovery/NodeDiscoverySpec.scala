package io.casperlabs.comm.discovery

import cats.effect.Timer
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.comm.discovery.NodeDiscoveryImpl.Millis
import io.casperlabs.comm.discovery.NodeDiscoverySpec.TextFixture
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.Log.NOPLog
import io.casperlabs.shared.{Log, Time}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Inspectors, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.util.Random

class NodeDiscoverySpec extends WordSpecLike with GeneratorDrivenPropertyChecks with Matchers {
  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 500
    )

  val genPeerNode: Gen[Node] =
    for {
      hash <- Gen.listOfN(20, Gen.choose(0, 255)).map(_.map(_.toByte))
      host <- Gen.listOfN(4, Gen.choose(0, 255)).map(xs => xs.mkString("."))
    } yield Node(ByteString.copyFrom(hash.toArray), host, 40400, 40404)

  val genSetPeerNodes: Gen[Set[Node]] =
    for {
      n     <- Gen.choose(3, 10)
      peers <- Gen.listOfN(n, genPeerNode)
    } yield peers.toSet

  val genFullyConnectedPeers: Gen[Map[Node, List[Node]]] =
    for {
      n     <- Gen.choose(4, 10)
      peers <- Gen.listOfN(n, genPeerNode)
    } yield peers.map { peer =>
      (peer, peers.filterNot(_ == peer))
    }.toMap

  /**
    * Target is the last element
    * Each peer knows next one like in linked list
    * Ordered by decreasing of XOR distance to the last element
    * Example:
    * 0 - lookup start from
    * 4 - target node, 4 XOR 4 == 0
    * 0->1 1->2 2->3 3->4
    */
  val genSequentiallyConnectedPeers: Gen[List[(Node, Node)]] =
    for {
      n      <- Gen.choose(4, 10)
      peers  <- Gen.listOfN(n, genPeerNode)
      target <- Gen.oneOf(peers)
      ordered = peers
        .sorted(
          (x: Node, y: Node) =>
            Ordering[BigInt].compare(
              PeerTable.xorDistance(x.id, target.id),
              PeerTable.xorDistance(y.id, target.id)
            )
        )
        .reverse
    } yield ordered.zip(ordered.tail)

  /** A generators .sample.get can sometimes return None, but these examples have no reason to not generate a result,
    * so defend against that and retry if it does happen.
    * Borrowed from [[io.casperlabs.comm.gossiping.ArbitraryConsensus]] */
  def sample[T](g: Gen[T]): T = {
    def loop(i: Int): T = {
      assert(i > 0, "Should be able to generate a sample.")
      g.sample.fold(loop(i - 1))(identity)
    }
    loop(10)
  }

  def totalN(peers: Map[Node, List[Node]]): Int =
    peers.toList.flatMap { case (k, vs) => k :: vs }.toSet.size

  def totalN(peers: List[(Node, Node)]): Int =
    peers.flatMap { case (l, r) => List(l, r) }.toSet.size

  def chooseRandom(peers: Map[Node, List[Node]]): Node =
    peers.keys.toList(Random.nextInt(peers.size))

  def chooseRandom(peers: Set[Node], n: Int): Set[Node] =
    Random.shuffle(peers.toList).take(n).toSet

  def chooseRandom(peers: List[Node]): Node =
    peers(Random.nextInt(peers.size))

  def toSet(peers: Map[Node, List[Node]]): Set[Node] =
    peers.toList.flatMap { case (k, vs) => k :: vs }.toSet

  def nextInt(n: Int): Int = Random.nextInt(n)

  /* Generates random int inclusive on both sides */
  def nextInt(from: Int, to: Int): Int =
    to - from + 1 match {
      case 0          => from
      case n if n < 0 => throw new IllegalArgumentException
      case n          => nextInt(n) + from
    }

  def ascendingDistance(peers: Set[Node]): List[Node] = peers.toList.sorted(
    (x: Node, y: Node) =>
      Ordering[BigInt].compare(
        PeerTable.xorDistance(NodeIdentifier(x.id), NodeDiscoverySpec.id),
        PeerTable.xorDistance(NodeIdentifier(y.id), NodeDiscoverySpec.id)
      )
  )

  def ascendingDistance(peers: Map[Node, List[Node]]): List[Node] = ascendingDistance(toSet(peers))

  "KademliaNodeDiscovery" when {
    "lookup" should {
      "quit early if asked peer already in peer table" in
        forAll(genFullyConnectedPeers) { peers: Map[Node, List[Node]] =>
          val target = chooseRandom(peers)
          TextFixture.prefilledTable(
            connections = peers,
            k = totalN(peers),
            alivePeersCacheSize = totalN(peers)
          ) { (kademlia, nd, _) =>
            for {
              _        <- nd.updateRecentlyAlivePeers
              response <- nd.lookup(NodeIdentifier(target.id))
            } yield {
              kademlia.totalLookups shouldBe 0
              response shouldBe Some(target)
            }
          }
        }
      "converge to the closest node if each peers knows next one and the target peer is the last" in
        forAll(genSequentiallyConnectedPeers) { peers: List[(Node, Node)] =>
          val target  = peers.last._2
          val initial = peers.head._1
          TextFixture.customInitial(
            connections = peers.toMap.mapValues(List(_)),
            tableInitial = Set(initial),
            k = totalN(peers),
            alpha = 1,
            alivePeersCacheSize = totalN(peers)
          ) { (kademlia, nd, _) =>
            for {
              _        <- nd.updateRecentlyAlivePeers
              response <- nd.lookup(NodeIdentifier(target.id))
            } yield {
              // 0 - initial, 4 - target
              // lookup goes by chain until it reaches the last element
              // last element is the closest to itself, but it will make one more request
              // 0->1 1->2 2->3 3->4 4->(empty)
              kademlia.totalLookups shouldBe peers.size + 1
              response shouldBe Some(target)
              ()
            }
          }
        }
      "fill the peer table with successfully responded peers" in
        forAll(genFullyConnectedPeers) { peers: Map[Node, List[Node]] =>
          //we need strong ordering
          val asList               = peers.toList
          val target               = chooseRandom(asList.init.map(_._1))
          val initialAlwaysHealthy = asList.last._1
          //fully parallel requests, so only 1 round with everyone
          val alpha = peers.size
          //at least 1 peer returns all others
          val failuresN = Random.nextInt(asList.size - 1) + 1
          val withFailures = (0 until failuresN)
            .foldLeft(asList) {
              case (acc, i) => acc.updated(i, (acc(i)._1, List.empty))
            }
            .map {
              case (k, vs) if vs.isEmpty => (k, None)
              case (k, vs)               => (k, Some(vs))
            }
            .toMap
          TextFixture.customInitialWithFailures(
            connections = withFailures,
            tableInitial = Set(initialAlwaysHealthy),
            k = totalN(peers),
            alpha = alpha,
            alivePeersCacheSize = totalN(peers)
          ) { (_, nd, _) =>
            for {
              _         <- nd.updateRecentlyAlivePeers
              _         <- nd.lookup(NodeIdentifier(target.id))
              fromTable <- nd.table.peersAscendingDistance
            } yield {
              fromTable should contain theSameElementsAs withFailures.collect {
                case (k, Some(_)) => k
              }
            }
          }
        }
      "skip itself" in forAll(genSetPeerNodes) { peers: Set[Node] =>
        val target              = peers.head
        val itself              = Node(NodeDiscoverySpec.id, "localhost", 40400, 40404)
        val allPointingToItself = peers.tail.map(p => (p, List(itself))).toMap
        TextFixture.prefilledTable(
          connections = allPointingToItself,
          k = peers.size,
          alivePeersCacheSize = peers.size
        ) { (kademlia, nd, _) =>
          for {
            _        <- nd.updateRecentlyAlivePeers
            response <- nd.lookup(NodeIdentifier(target.id))
          } yield {
            response shouldBe Some(itself)
            kademlia.lookupsBy(itself) shouldBe 0
          }
        }
      }
      "stop lookup when successfully called 'k' peers" in forAll(genSequentiallyConnectedPeers) {
        peers: List[(Node, Node)] =>
          val target  = peers.last._2
          val initial = peers.head._1
          val total   = peers.size
          val k       = nextInt(1, total)
          TextFixture.customInitial(
            connections = peers.toMap.mapValues(List(_)),
            tableInitial = Set(initial),
            k = k,
            alpha = 1,
            alivePeersCacheSize = totalN(peers)
          ) { (kademlia, nd, _) =>
            for {
              _        <- nd.updateRecentlyAlivePeers
              response <- nd.lookup(NodeIdentifier(target.id))
            } yield {
              kademlia.totalLookups shouldBe k
              response shouldBe Some(peers(k - 1)._2)
            }
          }
      }
      "stop lookup when no closer node returned in round" in forAll(genSequentiallyConnectedPeers) {
        peers: List[(Node, Node)] =>
          val target          = peers.last._2
          val indexToSwapWith = nextInt(peers.size - 1)
          // Move the closest element to middle of chain
          // 0->1 1->2 2->3 3->4
          // 4 - closest, swapped with 2
          // 0->1 1->4 4->3 3->2
          // should stop after 4->3
          val swapped = {
            val original                  = peers(indexToSwapWith)._2
            val updatedLast               = (peers.last._1, original)
            val updatedPointingToOriginal = (peers(indexToSwapWith)._1, target)
            val updatedNext               = (target, peers(indexToSwapWith + 1)._2)
            peers
              .updated(indexToSwapWith, updatedPointingToOriginal)
              .updated(indexToSwapWith + 1, updatedNext)
              .init :+ updatedLast
          }
          val initial = swapped.head._1
          TextFixture.customInitial(
            connections = swapped.toMap.mapValues(List(_)),
            tableInitial = Set(initial),
            k = totalN(peers),
            alpha = 1,
            alivePeersCacheSize = totalN(peers)
          ) { (kademlia, nd, _) =>
            for {
              _        <- nd.updateRecentlyAlivePeers
              response <- nd.lookup(NodeIdentifier(target.id))
            } yield {
              kademlia.totalLookups shouldBe indexToSwapWith + 2
              response shouldBe Some(target)
              ()
            }
          }
      }
      "perform at most 'alpha' concurrent requests" in forAll(genSequentiallyConnectedPeers) {
        peers: List[(Node, Node)] =>
          val target  = peers.last._2
          val initial = peers.head._1
          val alpha   = 2
          TextFixture.customInitial(
            connections = peers.toMap.mapValues(List(_)),
            tableInitial = Set(initial),
            k = totalN(peers),
            alpha = alpha,
            alivePeersCacheSize = totalN(peers)
          ) { (kademlia, nd, _) =>
            for {
              _ <- nd.updateRecentlyAlivePeers
              _ <- nd.lookup(NodeIdentifier(target.id))
            } yield {
              kademlia.concurrentLookups should be <= alpha
            }
          }
      }
    }
    "updateRecentlyAlivePeers" should {
      "start pinging all peers if size(alive peers from cache) < pingAllThreshold" in forAll(
        genFullyConnectedPeers
      ) { peers: Map[Node, List[Node]] =>
        val all              = toSet(peers)
        val pingAllThreshold = nextInt(1, all.size)
        val alive            = chooseRandom(all, nextInt(0, pingAllThreshold - 1))
        TextFixture.customInitial(
          connections = Map.empty,
          tableInitial = all,
          k = totalN(peers),
          alpha = 0,
          pings = Some(alive),
          // To ping all peers
          alivePeersCacheSize = totalN(peers),
          alivePeersCacheMinThreshold = pingAllThreshold
        ) { (kademlia, nd, _) =>
          for {
            // fills up cache
            _  <- nd.updateRecentlyAlivePeers
            r1 <- nd.recentlyAlivePeersAscendingDistance
            // each request should cause re-pinging all peers
            // firstly only alive peers from previous request
            // then rest of all known peers trying to fill up cache
            n = nextInt(1, 5)
            r2 <- (nd.updateRecentlyAlivePeers >> nd.recentlyAlivePeersAscendingDistance)
                   .replicateA(n)
          } yield {
            kademlia.totalPings shouldBe (totalN(peers) * (n + 1))
            r1 should contain theSameElementsInOrderAs ascendingDistance(alive)
            Inspectors.forAll(r2) { r =>
              r should contain theSameElementsInOrderAs ascendingDistance(alive)
            }
          }
        }
      }
      "not ping all peers if size(alive peers from cache) >= pingAllThreshold" in forAll(
        genFullyConnectedPeers
      ) { peers: Map[Node, List[Node]] =>
        val all              = toSet(peers)
        val pingAllThreshold = nextInt(1, all.size)
        val alive            = chooseRandom(all, nextInt(pingAllThreshold, all.size))
        TextFixture.customInitial(
          connections = Map.empty,
          tableInitial = all,
          k = totalN(peers),
          alpha = 0,
          pings = Some(alive),
          // To ping all peers
          alivePeersCacheSize = totalN(peers),
          alivePeersCacheMinThreshold = pingAllThreshold,
          // Sequential requests to make cache size deterministic
          alivePeersCachePingsBatchSize = 1
        ) { (kademlia, nd, _) =>
          for {
            // fills up cache
            _  <- nd.updateRecentlyAlivePeers
            r1 <- nd.recentlyAlivePeersAscendingDistance
            // pings alive peers from previous query
            _  <- nd.updateRecentlyAlivePeers
            r2 <- nd.recentlyAlivePeersAscendingDistance
          } yield {
            kademlia.totalPings shouldBe (all.size + alive.size)
            r1 should contain theSameElementsInOrderAs ascendingDistance(alive)
            r2 should contain theSameElementsInOrderAs ascendingDistance(alive)
          }
        }
      }
      "cleanup cache if expired" in {
        val peers: Map[Node, List[Node]] = sample(genFullyConnectedPeers)
        TextFixture.customInitial(
          connections = Map.empty,
          tableInitial = toSet(peers),
          k = totalN(peers),
          alpha = 0,
          Some(toSet(peers)),
          // To ping all peers
          alivePeersCacheSize = totalN(peers),
          // To ignore how much alive peers check
          alivePeersCacheMinThreshold = 0,
          alivePeersCacheExpirationPeriod = 50.milliseconds
        ) { (kademlia, nd, _) =>
          for {
            _  <- nd.updateRecentlyAlivePeers
            r1 <- nd.recentlyAlivePeersAscendingDistance
            _  <- Task.sleep(200.milliseconds)
            _  <- nd.updateRecentlyAlivePeers
            r2 <- nd.recentlyAlivePeersAscendingDistance
          } yield {
            // 1st => to fill up cache
            // 2nd => to check alive peers from cache
            // 3rd => to re-fill cache with alive peers
            kademlia.totalPings shouldBe (peers.size * 3)
            r1 should contain theSameElementsInOrderAs ascendingDistance(peers)
            r2 should contain theSameElementsInOrderAs ascendingDistance(peers)
          }
        }
      }
      "stop pinging if desired cache size is reached" in forAll(genFullyConnectedPeers) {
        peers: Map[Node, List[Node]] =>
          TextFixture.customInitial(
            connections = Map.empty,
            tableInitial = toSet(peers),
            k = totalN(peers),
            alpha = 0,
            pings = Some(toSet(peers)),
            alivePeersCacheSize = 1,
            // Sequential requests
            alivePeersCachePingsBatchSize = 1
          ) { (kademlia, nd, _) =>
            for {
              _ <- nd.updateRecentlyAlivePeers
              _ <- nd.recentlyAlivePeersAscendingDistance
            } yield {
              kademlia.totalPings shouldBe 1
            }
          }
      }
      "update cache with specified period" in {
        val peers: Map[Node, List[Node]] = sample(genFullyConnectedPeers)
        TextFixture.customInitial(
          connections = Map.empty,
          tableInitial = toSet(peers),
          k = totalN(peers),
          alpha = 0,
          pings = Some(toSet(peers)),
          alivePeersCacheSize = totalN(peers),
          // To cause update on next cycle
          alivePeersCacheMinThreshold = totalN(peers) + 1,
          alivePeersCacheUpdatePeriod = 100.milliseconds
        ) { (kademlia, nd, _) =>
          for {
            _ <- nd.schedulePeriodicRecentlyAlivePeersCacheUpdate.start
            _ <- Task.sleep(150.millis)
          } yield {
            kademlia.totalPings shouldBe (totalN(peers) * 2)
          }
        }
      }
    }
  }
}

object NodeDiscoverySpec {

  class KademliaMock(connections: Map[Node, Option[List[Node]]], alive: Node => Boolean)
      extends KademliaService[Task] {
    private val lookupsByCallee      = Atomic(Map.empty[Node, Int].withDefaultValue(0))
    private val pings                = Atomic(0)
    private val maxConcurrentPings   = Atomic(0)
    private val pingsConcurrency     = Atomic(0)
    private val maxConcurrentLookups = Atomic(0)
    private val lookupsConcurrency   = Atomic(0)
    def totalLookups: Int            = lookupsByCallee.get().values.sum
    def totalPings: Int              = pings.get()
    def lookupsBy(peer: Node): Int   = lookupsByCallee.get()(peer)
    def concurrentLookups: Int       = maxConcurrentLookups.get()
    def concurrentPings: Int         = maxConcurrentPings.get()
    override def ping(node: Node): Task[Boolean] =
      Task {
        pingsConcurrency.increment()
        maxConcurrentPings.transform(math.max(_, pingsConcurrency.get()))
        pingsConcurrency.decrement()
        pings.increment()
        alive(node)
      }
    override def lookup(id: NodeIdentifier, peer: Node): Task[Option[Seq[Node]]] =
      Task {
        lookupsConcurrency.increment()
        maxConcurrentLookups.transform(math.max(_, lookupsConcurrency.get()))
        lookupsConcurrency.decrement()
        lookupsByCallee.transform(m => m.updated(peer, m(peer) + 1))
        connections.getOrElse(peer, None)
      }
    override def receive(
        pingHandler: Node => Task[Unit],
        lookupHandler: (Node, NodeIdentifier) => Task[Seq[Node]]
    ): Task[Unit]                       = ???
    override def shutdown(): Task[Unit] = ???
  }

  implicit val logNoOp: Log[Task] = new NOPLog[Task]
  implicit val time: Time[Task] = new Time[Task] {
    def currentMillis: Task[Long]                   = Timer[Task].clock.realTime(MILLISECONDS)
    def nanoTime: Task[Long]                        = Timer[Task].clock.monotonic(NANOSECONDS)
    def sleep(duration: FiniteDuration): Task[Unit] = Timer[Task].sleep(duration)
  }
  implicit val metricsNOP: Metrics[Task] = new MetricsNOP[Task]
  val id                                 = NodeIdentifier(List.fill(20)(0.toByte))

  object TextFixture {
    def customInitialWithFailures(
        connections: Map[Node, Option[List[Node]]],
        tableInitial: Set[Node],
        k: Int,
        alpha: Int = 2,
        pings: Option[Set[Node]] = None,
        alivePeersCacheSize: Int = 20,
        alivePeersCacheMinThreshold: Int = 10,
        alivePeersCacheExpirationPeriod: FiniteDuration = 10.minutes,
        alivePeersCacheUpdatePeriod: FiniteDuration = 1.minute,
        alivePeersCachePingsBatchSize: Int = 10
    )(test: (KademliaMock, NodeDiscoveryImpl[Task], Int) => Task[Unit]): Unit = {
      val effect = for {
        table                 <- PeerTable[Task](id, k)
        recentlyAlivePeersRef <- Ref.of[Task, (Set[Node], Millis)]((Set.empty[Node], 0L))
        implicit0(kademliaMock: KademliaMock) = new KademliaMock(
          connections,
          pings.getOrElse(_ => true)
        )
        _ <- tableInitial.toList.traverse(table.updateLastSeen)
        nd = new NodeDiscoveryImpl[Task](
          id,
          table,
          recentlyAlivePeersRef,
          alpha,
          k,
          true,
          alivePeersCacheSize,
          alivePeersCacheMinThreshold,
          alivePeersCacheExpirationPeriod,
          alivePeersCacheUpdatePeriod,
          alivePeersCachePingsBatchSize
        )
        _ <- test(kademliaMock, nd, alpha)
      } yield ()
      effect.runSyncUnsafe(5.seconds)
    }

    def customInitial(
        connections: Map[Node, List[Node]],
        tableInitial: Set[Node],
        k: Int,
        alpha: Int = 2,
        pings: Option[Set[Node]] = None,
        alivePeersCacheSize: Int = 20,
        alivePeersCacheMinThreshold: Int = 10,
        alivePeersCacheExpirationPeriod: FiniteDuration = 10.minutes,
        alivePeersCacheUpdatePeriod: FiniteDuration = 1.minute,
        alivePeersCachePingsBatchSize: Int = 10
    )(test: (KademliaMock, NodeDiscoveryImpl[Task], Int) => Task[Unit]): Unit =
      customInitialWithFailures(
        connections.mapValues(Option(_)),
        tableInitial,
        k,
        alpha,
        pings,
        alivePeersCacheSize,
        alivePeersCacheMinThreshold,
        alivePeersCacheExpirationPeriod,
        alivePeersCacheUpdatePeriod,
        alivePeersCachePingsBatchSize
      )(test)

    def prefilledTable(
        connections: Map[Node, List[Node]],
        k: Int,
        alpha: Int = 2,
        pings: Option[Set[Node]] = None,
        alivePeersCacheSize: Int = 20,
        alivePeersCacheMinThreshold: Int = 10,
        alivePeersCacheExpirationPeriod: FiniteDuration = 10.minutes,
        alivePeersCacheUpdatePeriod: FiniteDuration = 1.minute,
        alivePeersCachePingsBatchSize: Int = 10
    )(test: (KademliaMock, NodeDiscoveryImpl[Task], Int) => Task[Unit]): Unit =
      customInitial(
        connections,
        connections
          .flatMap { case (key, values) => key :: values }
          .filterNot(p => p.id.toByteArray sameElements id.key)
          .toSet,
        k,
        alpha,
        pings,
        alivePeersCacheSize,
        alivePeersCacheMinThreshold,
        alivePeersCacheExpirationPeriod,
        alivePeersCacheUpdatePeriod,
        alivePeersCachePingsBatchSize
      )(test)
  }
}
