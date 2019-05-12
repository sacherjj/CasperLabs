package io.casperlabs.comm.gossiping

import cats.Applicative
import cats.effect.Sync
import cats.mtl.DefaultApplicativeAsk
import cats.syntax.option._
import cats.temp.par.Par
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Log
import io.casperlabs.shared.Log.NOPLog
import io.casperlabs.metrics.Metrics
import monix.eval.Task
import monix.eval.instances.CatsParallelForTask
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import monix.tail.Iterant
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Inspectors, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.util.Random

class RelayingSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {
  import RelayingSpec._
  private val genListNode: Gen[List[Node]] =
    for {
      n     <- Gen.choose(2, 10)
      nodes <- Gen.listOfN(n, arbitrary[Node])
    } yield nodes

  private implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 50
    )

  "Relaying" when {
    "scheduled to relay blocks" should {
      "try to achieve certain level of 'relay factor" in
        forAll(genListNode, genHash) { (peers: List[Node], hash: ByteString) =>
          TestFixture(peers.size / 2, 0, peers, acceptOrFailure = _ => false.some) {
            (relaying, asked, _) =>
              for {
                _ <- relaying.relay(List(hash))
              } yield asked.get() shouldBe (peers.size / 2)
          }
        }

      "stop trying to relay a block if already achieved specified relay saturation" in
        forAll(genListNode, genHash) { (peers: List[Node], hash: ByteString) =>
          TestFixture(1, 50, peers, acceptOrFailure = _ => false.some) { (relaying, asked, _) =>
            for {
              _ <- relaying.relay(List(hash))
            } yield asked.get() shouldBe 2
          }
        }

      "stop trying to relay a block if already gossiped to 'relay factor' number of peers" in
        forAll(genListNode, genHash) { (peers: List[Node], hash: ByteString) =>
          val relayFactor = Random.nextInt(peers.size) + 1
          TestFixture(relayFactor, 100, peers, acceptOrFailure = _ => true.some) {
            (relaying, asked, _) =>
              for {
                _ <- relaying.relay(List(hash))
              } yield asked.get() shouldBe relayFactor
          }
        }
      "not gossip to more than 'relay factor' number of peers that responded positively" in
        forAll(genListNode, genHash) { (peers: List[Node], hash: ByteString) =>
          val relayFactor  = Random.nextInt(peers.size) + 1
          val responses    = peers.map(_ => Random.nextBoolean)
          val responseIter = responses.iterator
          TestFixture(
            relayFactor,
            100,
            peers,
            acceptOrFailure = _ => synchronized(responseIter.next.some)
          ) { (relaying, asked, _) =>
            for {
              _ <- relaying.relay(List(hash))
            } yield responses.take(asked.get()).count(identity) should be <= relayFactor
          }
        }
      "not stop gossiping if received an error" in
        forAll(genListNode, genHash) { (peers: List[Node], hash: ByteString) =>
          val log = new LogStub[Task]()

          TestFixture(peers.size, 100, peers, acceptOrFailure = _ => none[Boolean], log) {
            (relaying, asked, _) =>
              for {
                _ <- relaying.relay(List(hash))
              } yield {
                asked.get() shouldBe peers.size
                log.debugs.size shouldBe peers.size
                Inspectors.forAll(log.debugs)(msg => msg should include("NewBlocks request failed"))
              }
          }
        }
      "relay blocks in parallel" in
        forAll(genListNode, genHash, genHash, genHash) {
          (peers: List[Node], hash1: ByteString, hash2: ByteString, hash3) =>
            TestFixture(peers.size, 100, peers, acceptOrFailure = _ => false.some) {
              (relaying, _, maxConcurrentRequests) =>
                for {
                  _ <- relaying.relay(List(hash1, hash2, hash3))
                } yield maxConcurrentRequests.get() should be > 1
            }
        }
    }
  }
}

object RelayingSpec {
  private val local = Node(NodeIdentifier("0000"), "localhost", 40400, 40404)

  private val ask: NodeAsk[Task] = new DefaultApplicativeAsk[Task, Node] {
    val applicative: Applicative[Task] = Applicative[Task]
    def ask: Task[Node]                = Task.pure(local)
  }

  private val noOpLog: Log[Task] = new NOPLog[Task]
  implicit val metrics           = new Metrics.MetricsNOP[Task]

  object TestFixture {
    def apply(
        relayFactor: Int,
        relaySaturation: Int,
        peers: List[Node],
        acceptOrFailure: Node => Option[Boolean],
        log: Log[Task] = noOpLog
    )(test: (Relaying[Task], AtomicInt, AtomicInt) => Task[Unit]): Unit = {
      val nd = new NodeDiscovery[Task] {
        override def discover: Task[Unit]                           = ???
        override def lookup(id: NodeIdentifier): Task[Option[Node]] = ???
        override def alivePeersAscendingDistance: Task[List[Node]]  = Task.now(peers)
      }
      val asked                 = AtomicInt(0)
      val concurrency           = AtomicInt(0)
      val maxConcurrentRequests = AtomicInt(0)

      val gossipService = (peer: Node) =>
        for {
          _ <- Task.delay {
                asked.increment()
                concurrency.increment()
              }
          // We need to ensure that requests are concurrent
          _ <- Task.sleep(10.millis)
          _ <- Task.delay {
                maxConcurrentRequests.transform(math.max(_, concurrency.get()))
                concurrency.decrement()
              }
        } yield
          new GossipService[Task] {
            override def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] =
              acceptOrFailure(peer).fold(
                Task.raiseError[NewBlocksResponse](new RuntimeException("Boom"))
              )(accepted => Task.now(NewBlocksResponse(accepted)))

            override def streamAncestorBlockSummaries(
                request: StreamAncestorBlockSummariesRequest
            ) = ???
            override def streamDagTipBlockSummaries(request: StreamDagTipBlockSummariesRequest) =
              ???
            override def streamBlockSummaries(request: StreamBlockSummariesRequest) = ???
            override def getBlockChunked(request: GetBlockChunkedRequest)           = ???
            override def addApproval(request: AddApprovalRequest)                   = ???
            override def getGenesisCandidate(request: GetGenesisCandidateRequest)   = ???
          }

      val relayingImpl = RelayingImpl[Task](nd, gossipService, relayFactor, relaySaturation)(
        Sync[Task],
        Par.fromParallel(CatsParallelForTask),
        log,
        metrics,
        ask
      )
      test(relayingImpl, asked, maxConcurrentRequests).runSyncUnsafe(5.seconds)
    }
  }
}
