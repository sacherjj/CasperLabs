package io.casperlabs.comm.gossiping

import cats.Applicative
import cats.mtl.DefaultApplicativeAsk
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import monix.tail.Iterant
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.duration._
import scala.util.Random

class RelayingSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {
  import RelayingSpec._
  private implicit val consensusConfig: ConsensusConfig = ConsensusConfig()
  private implicit val arbListNode: Arbitrary[List[Node]] = Arbitrary {
    for {
      n     <- Gen.choose(2, 10)
      nodes <- Gen.listOfN(n, arbitrary[Node])
    } yield nodes
  }
  private implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 500
    )

  private val summary = summaryOf(sample(arbitrary[Block]))

  "Relaying" when {
    "scheduled to relay a block" should {
      "try to achieve certain level of 'relay factor" in
        forAll { peers: List[Node] =>
          TestFixture(peers.size / 2, 0, peers, _ => false) { (relaying, asked) =>
            for {
              _ <- relaying.relay(summary)
            } yield asked.get() shouldBe (peers.size / 2)
          }
        }

      "stop trying to relay a block if already achieved specified relay saturation" in
        forAll { peers: List[Node] =>
          TestFixture(1, 50, peers, _ => false) { (relaying, asked) =>
            for {
              _ <- relaying.relay(summary)
            } yield asked.get() shouldBe 2
          }
        }

      "stop trying to relay a block if already gossiped to 'relay factor' number of peers" in
        forAll { peers: List[Node] =>
          val relayFactor = Random.nextInt(peers.size) + 1
          TestFixture(relayFactor, 100, peers, _ => true) { (relaying, asked) =>
            for {
              _ <- relaying.relay(summary)
            } yield asked.get() shouldBe relayFactor
          }
        }
    }
  }
}

object RelayingSpec {
  private val local = Node(NodeIdentifier("0000"), "localhost", 40400, 40404)

  private implicit val ask: NodeAsk[Task] = new DefaultApplicativeAsk[Task, Node] {
    val applicative: Applicative[Task] = Applicative[Task]
    def ask: Task[Node]                = Task.pure(local)
  }

  def summaryOf(block: Block): BlockSummary =
    BlockSummary()
      .withBlockHash(block.blockHash)
      .withHeader(block.getHeader)
      .withSignature(block.getSignature)

  class GossipServiceMock extends GossipService[Task] {
    override def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] = ???

    override def streamAncestorBlockSummaries(
        request: StreamAncestorBlockSummariesRequest): Iterant[Task, BlockSummary] = ???

    override def streamDagTipBlockSummaries(
        request: StreamDagTipBlockSummariesRequest): Iterant[Task, BlockSummary] = ???

    override def streamBlockSummaries(
        request: StreamBlockSummariesRequest): Iterant[Task, BlockSummary] = ???

    /** Get a full block in chunks, optionally compressed, so that it can be transferred over the wire. */
    override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[Task, Chunk] = ???
  }

  object TestFixture {
    def apply(relayFactor: Int, relaySaturation: Int, peers: List[Node], accept: Node => Boolean)(
        test: (Relaying[Task], AtomicInt) => Task[Unit]): Unit = {
      val nd = new NodeDiscovery[Task] {
        override def discover: Task[Unit]                           = ???
        override def lookup(id: NodeIdentifier): Task[Option[Node]] = ???
        override def alivePeersAscendingDistance: Task[List[Node]]  = Task.now(peers)
      }
      val asked = AtomicInt(0)

      val gossipService = (peer: Node) =>
        Task.delay {
          asked.increment()
          new GossipService[Task] {
            override def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] =
              Task.now(NewBlocksResponse(accept(peer)))

            override def streamAncestorBlockSummaries(
                request: StreamAncestorBlockSummariesRequest): Iterant[Task, BlockSummary] = ???

            override def streamDagTipBlockSummaries(
                request: StreamDagTipBlockSummariesRequest): Iterant[Task, BlockSummary] = ???

            override def streamBlockSummaries(
                request: StreamBlockSummariesRequest): Iterant[Task, BlockSummary] = ???

            /** Get a full block in chunks, optionally compressed, so that it can be transferred over the wire. */
            override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[Task, Chunk] =
              ???
          }
      }
      val relayingImpl = new RelayingImpl[Task](nd, gossipService, relayFactor, relaySaturation)
      test(relayingImpl, asked).runSyncUnsafe(5.seconds)
    }
  }
}
