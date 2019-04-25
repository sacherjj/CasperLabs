package io.casperlabs.comm.gossiping

import cats.effect.concurrent.Semaphore
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Approval, BlockSummary}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping.InitialSyncSpec.TestFixture
import io.casperlabs.shared.Log.NOPLog
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic
import monix.tail.Iterant
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, FunSuiteLike, Matchers}

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

class InitialSyncSpec
    extends FunSuiteLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {
  private implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  def genNodes(min: Int = 1, max: Int = 10) =
    Gen.choose(min, max).flatMap(n => Gen.listOfN(n, arbNode.arbitrary))

  def genTips(n: Int) = Gen.listOfN(n, arbBlockSummary.arbitrary)

  test("should propagate errors to invoker") {
    forAll(genNodes(), arbBlockSummary.arbitrary) { (nodes, tip) =>
      val e = new RuntimeException("Boom!")
      TestFixture(
        InitialSynchronizationImpl.Bootstrap(nodes.head),
        nodes,
        (_, _) => nodes,
        _ => List(tip),
        (_, _) => Task.raiseError(e)
      ) { (sync, _) =>
        for {
          w <- sync
          r <- w.attempt
        } yield {
          r.isLeft shouldBe true
          r.left.get shouldBe e
        }
      }
    }
  }

  test("should resolve handle when there are no new tips and ask all specified nodes") {
    forAll(genNodes(), arbBlockSummary.arbitrary) { (nodes, tip) =>
      val atomicBoolean = Atomic(true)
      val selectedNodes = sample(Gen.choose(1, nodes.size).flatMap(i => Gen.pick(i, nodes)))
      TestFixture(
        InitialSynchronizationImpl.Bootstrap(nodes.head),
        nodes,
        (_, _) => selectedNodes.toList,
        _ => List(tip),
        (_, _) => Task.delay(atomicBoolean.get())
      ) { (sync, mock) =>
        for {
          w1 <- sync
          r1 <- w1.timeout(200.millis).attempt
          _ <- Task {
                r1.isLeft shouldBe true
                r1.left.get shouldBe an[TimeoutException]
              }
          w2 <- sync
          _ <- Task {
                atomicBoolean.set(false)
              }
          r2 <- w2.attempt
        } yield {
          r2.isRight shouldBe true
          mock.asked.get() should contain allElementsOf selectedNodes
        }
      }
    }
  }
}

object InitialSyncSpec extends ArbitraryConsensus {
  implicit val logNoOp = new NOPLog[Task]

  class MockNodeDiscovery(nodes: List[Node]) extends NodeDiscovery[Task] {
    def discover                    = ???
    def lookup(id: NodeIdentifier)  = ???
    def alivePeersAscendingDistance = Task.now(nodes)
  }

  object MockBackend extends GossipServiceServer.Backend[Task] {
    def hasBlock(blockHash: ByteString)        = ???
    def getBlockSummary(blockHash: ByteString) = ???
    def getBlock(blockHash: ByteString)        = ???
  }

  object MockSynchronizer extends Synchronizer[Task] {
    def syncDag(source: Node, targetBlockHashes: Set[ByteString]) = ???
  }

  object MockDownloadManager extends DownloadManager[Task] {
    def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean) = ???
  }

  object MockConsensus extends GossipServiceServer.Consensus[Task] {
    def onPending(dag: Vector[BlockSummary]) = ???
    def onDownloaded(blockHash: ByteString)  = ???
    def listTips                             = ???
  }

  object MockGenesisApprover extends GenesisApprover[Task] {
    def getCandidate                                           = ???
    def addApproval(blockHash: ByteString, approval: Approval) = ???
    def awaitApproval                                          = ???
  }

  val MockSemaphore = Semaphore[Task](1).runSyncUnsafe(1.second)

  class MockGossipServiceServer(sync: (Node, Seq[ByteString]) => Task[Boolean])
      extends GossipServiceServer[Task](
        MockBackend,
        MockSynchronizer,
        MockDownloadManager,
        MockConsensus,
        MockGenesisApprover,
        0,
        MockSemaphore
      ) {
    val asked = Atomic(Vector.empty[Node])

    override protected[gossiping] def newBlocksSynchronous(
        request: NewBlocksRequest
    ): Task[NewBlocksResponse] = {
      asked.transform(_ :+ request.getSender)
      sync(request.getSender, request.blockHashes).map(b => NewBlocksResponse(isNew = b))
    }
  }

  object TestFixture {
    def apply(
        bootstrap: InitialSynchronizationImpl.Bootstrap,
        nodes: List[Node],
        selectNodes: (InitialSynchronizationImpl.Bootstrap, List[Node]) => List[Node],
        tips: Node => List[BlockSummary],
        sync: (Node, Seq[ByteString]) => Task[Boolean]
    )(test: (Task[Task[Unit]], MockGossipServiceServer) => Task[Unit]): Unit = {
      val mockGossipServiceServer = new MockGossipServiceServer(sync)
      val effect = new InitialSynchronizationImpl(
        new MockNodeDiscovery(nodes),
        mockGossipServiceServer,
        bootstrap,
        selectNodes, { node =>
          new GossipService[Task] {
            def newBlocks(request: NewBlocksRequest)                                       = ???
            def streamAncestorBlockSummaries(request: StreamAncestorBlockSummariesRequest) = ???
            def streamDagTipBlockSummaries(request: StreamDagTipBlockSummariesRequest) =
              Iterant.fromList[Task, BlockSummary](tips(node))
            def streamBlockSummaries(request: StreamBlockSummariesRequest) = ???
            def getBlockChunked(request: GetBlockChunkedRequest)           = ???
            def getGenesisCandidate(request: GetGenesisCandidateRequest)   = ???
            def addApproval(request: AddApprovalRequest)                   = ???
          }
        }
      )
      test(effect.syncOnStartup(100.millis), mockGossipServiceServer).runSyncUnsafe(5.seconds)
    }
  }
}
