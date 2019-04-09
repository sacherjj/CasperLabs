package io.casperlabs.comm.discovery

import cats.effect.Timer
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.comm.discovery.NodeDiscoverySpec.TextFixture
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.Log.NOPLog
import io.casperlabs.shared.{Log, Time}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.{Atomic, AtomicInt}
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpecLike}

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
    } yield
      peers.map { peer =>
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

  def totalN(peers: Map[Node, List[Node]]): Int =
    peers.toList.flatMap { case (k, vs) => k :: vs }.toSet.size

  def totalN(peers: List[(Node, Node)]): Int =
    peers.flatMap { case (l, r) => List(l, r) }.toSet.size

  "KademliaNodeDiscovery" when {
    "lookup" should {
      "quit early if asked peer already in peer table" in
        forAll(genFullyConnectedPeers) { peers: Map[Node, List[Node]] =>
          val target = peers.keys.toList(Random.nextInt(peers.size))
          TextFixture.prefilledTable(NodeIdentifier(target.id), peers, totalN(peers)) {
            (kademlia, nd, alpha) =>
              for {
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
            NodeIdentifier(target.id),
            peers.toMap.mapValues(List(_)),
            Set(initial),
            totalN(peers),
            1
          ) { (kademlia, nd, _) =>
            for {
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
          val target               = asList.init.map(_._1).apply(Random.nextInt(asList.init.size))
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
            NodeIdentifier(target.id),
            withFailures,
            Set(initialAlwaysHealthy),
            totalN(peers),
            alpha
          ) { (_, nd, _) =>
            for {
              _         <- nd.lookup(NodeIdentifier(target.id))
              fromTable <- nd.alivePeersAscendingDistance
            } yield {
              fromTable should contain theSameElementsAs withFailures.collect {
                case (k, Some(_)) => k
              }
            }
          }
        }
      "skip itself" in
        forAll(genSetPeerNodes) { peers: Set[Node] =>
          val target              = peers.head
          val itself              = Node(NodeDiscoverySpec.id, "localhost", 40400, 40404)
          val allPointingToItself = peers.tail.map(p => (p, List(itself))).toMap
          TextFixture.prefilledTable(NodeIdentifier(target.id), allPointingToItself, peers.size) {
            (kademlia, nd, _) =>
              for {
                response <- nd.lookup(NodeIdentifier(target.id))
              } yield {
                response shouldBe Some(itself)
                kademlia.lookupsBy(itself) shouldBe 0
              }
          }
        }
      "stop lookup when successfully called 'k' peers" in
        forAll(genSequentiallyConnectedPeers) { peers: List[(Node, Node)] =>
          val target  = peers.last._2
          val initial = peers.head._1
          val total   = peers.size
          val k       = Random.nextInt(total) + 1
          TextFixture.customInitial(
            NodeIdentifier(target.id),
            peers.toMap.mapValues(List(_)),
            Set(initial),
            k,
            1
          ) { (kademlia, nd, _) =>
            for {
              response <- nd.lookup(NodeIdentifier(target.id))
            } yield {
              kademlia.totalLookups shouldBe k
              response shouldBe Some(peers(k - 1)._2)
            }
          }
        }
      "stop lookup when no closer node returned in round" in
        forAll(genSequentiallyConnectedPeers) { peers: List[(Node, Node)] =>
          val target          = peers.last._2
          val indexToSwapWith = Random.nextInt(peers.size - 1)
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
            NodeIdentifier(target.id),
            swapped.toMap.mapValues(List(_)),
            Set(initial),
            totalN(peers),
            1
          ) { (kademlia, nd, _) =>
            for {
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
            NodeIdentifier(target.id),
            peers.toMap.mapValues(List(_)),
            Set(initial),
            totalN(peers),
            alpha
          ) { (kademlia, nd, _) =>
            for {
              _ <- nd.lookup(NodeIdentifier(target.id))
            } yield {
              kademlia.concurrentRequests should be <= alpha
            }
          }
      }
    }
    "alivePeersAscendingDistance" should {
      "return only alive peers in ascending distance to itself" in {
        forAll(genFullyConnectedPeers) { peers: Map[Node, List[Node]] =>
          val target = peers.keys.toList(Random.nextInt(peers.size))
          val all    = peers.toList.flatMap { case (k, vs) => k :: vs }.toSet
          val alive  = all.filter(_ => Random.nextBoolean())
          TextFixture.customInitial(
            NodeIdentifier(target.id),
            Map.empty,
            all,
            all.size,
            0,
            Some(alive)
          ) { (_, nd, _) =>
            for {
              response <- nd.alivePeersAscendingDistance
            } yield {
              response should contain theSameElementsInOrderAs alive.toList.sorted(
                (x: Node, y: Node) =>
                  Ordering[BigInt].compare(
                    PeerTable.xorDistance(NodeIdentifier(x.id), NodeDiscoverySpec.id),
                    PeerTable.xorDistance(NodeIdentifier(y.id), NodeDiscoverySpec.id)
                  )
              )
            }
          }
        }
      }
    }
  }
}

object NodeDiscoverySpec {

  class KademliaMock(peers: Map[Node, Option[List[Node]]], alive: Node => Boolean)
      extends KademliaService[Task] {
    private val lookupsByCallee                  = Atomic(Map.empty[Node, Int].withDefaultValue(0))
    private val maxConcurrentRequests            = AtomicInt(0)
    private val concurrency                      = AtomicInt(0)
    def totalLookups: Int                        = lookupsByCallee.get().values.sum
    def lookupsBy(peer: Node): Int               = lookupsByCallee.get()(peer)
    def concurrentRequests: Int                  = maxConcurrentRequests.get()
    override def ping(node: Node): Task[Boolean] = Task.now(alive(node))
    override def lookup(id: NodeIdentifier, peer: Node): Task[Option[Seq[Node]]] =
      Task {
        concurrency.increment()
        maxConcurrentRequests.transform(math.max(_, concurrency.get()))
        concurrency.decrement()
        lookupsByCallee.transform(m => m.updated(peer, m(peer) + 1))
        peers.getOrElse(peer, None)
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
        toLookup: NodeIdentifier,
        peers: Map[Node, Option[List[Node]]],
        initial: Set[Node],
        k: Int,
        alpha: Int = 2,
        pings: Option[Set[Node]] = None
    )(test: (KademliaMock, NodeDiscoveryImpl[Task], Int) => Task[Unit]): Unit =
      PeerTable[Task](id, k)
        .flatMap { table =>
          implicit val K: KademliaMock = new KademliaMock(peers, pings.getOrElse(_ => true))
          val fillTable = initial.toList
            .traverse(table.updateLastSeen)
            .void
          fillTable
            .map(_ => (K, new NodeDiscoveryImpl[Task](id, table, alpha, k)))
        }
        .flatMap { case (kademlia, nd) => test(kademlia, nd, alpha) }
        .runSyncUnsafe(5.seconds)

    def customInitial(
        toLookup: NodeIdentifier,
        peers: Map[Node, List[Node]],
        initial: Set[Node],
        k: Int,
        alpha: Int = 2,
        pings: Option[Set[Node]] = None
    )(test: (KademliaMock, NodeDiscoveryImpl[Task], Int) => Task[Unit]): Unit =
      customInitialWithFailures(toLookup, peers.mapValues(Option(_)), initial, k, alpha, pings)(
        test
      )

    def prefilledTable(
        toLookup: NodeIdentifier,
        peers: Map[Node, List[Node]],
        k: Int,
        alpha: Int = 2,
        pings: Option[Set[Node]] = None
    )(test: (KademliaMock, NodeDiscoveryImpl[Task], Int) => Task[Unit]): Unit =
      customInitial(
        toLookup,
        peers,
        peers
          .flatMap { case (key, values) => key :: values }
          .filterNot(p => p.id.toByteArray sameElements id.key)
          .toSet,
        k,
        alpha,
        pings
      )(test)
  }
}
