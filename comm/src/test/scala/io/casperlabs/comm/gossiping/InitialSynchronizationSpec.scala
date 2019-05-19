package io.casperlabs.comm.gossiping

import java.util.concurrent.TimeoutException

import cats.effect.concurrent.Semaphore
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.{Approval, BlockSummary, GenesisCandidate}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping.InitialSynchronizationImpl.{Bootstrap, SynchronizationError}
import io.casperlabs.comm.gossiping.InitialSynchronizationSpec.TestFixture
import io.casperlabs.shared.Log.NOPLog
import io.casperlabs.metrics.Metrics
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic
import monix.tail.Iterant
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Inspectors, Matchers, WordSpecLike}

import scala.concurrent.duration._

class InitialSynchronizationSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {
  private implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  def genNodes(min: Int = 1, max: Int = 10) =
    Gen.choose(min, max).flatMap(n => Gen.listOfN(n, arbNode.arbitrary))

  def genTips(n: Int = 10) = Gen.listOfN(n, arbBlockSummary.arbitrary)

  def pos(n: Int): Int Refined Positive = refineV[Positive](n).right.get

  "syncOnStartup" when {
    "specified to memoize nodes between rounds" should {
      def test(skipFailedNodesInNextRound: Boolean): Unit = forAll(genNodes(), genTips()) {
        (nodes, tips) =>
          val counter = Atomic(0)

          TestFixture(
            nodes,
            tips,
            memoizeNodes = true,
            selectNodes = { (bootstrap, nodes) =>
              counter.increment()
              bootstrap :: nodes
            },
            minSuccessful = pos(nodes.size),
            sync = (_, _) => Task.raiseError(new RuntimeException),
            skipFailedNodesInNextRounds = skipFailedNodesInNextRound
          ) { (initialSynchronizer, _) =>
            for {
              w <- initialSynchronizer.sync()
              _ <- w.timeout(75.millis).attempt
            } yield {
              counter.get() shouldBe 1
            }
          }
      }

      "not apply selectNodes function more than once if skipFailingNodesInNextRound is true" in {
        test(skipFailedNodesInNextRound = true)
      }
      "not apply selectNodes function more than once if skipFailingNodesInNextRound is false" in {
        test(skipFailedNodesInNextRound = false)
      }
    }
    "specified to skip nodes failed to response" should {
      def test(memoize: Boolean): Unit =
        forAll(genNodes().map(_.toSet), genNodes().map(_.toSet), genTips()) {
          (successfulNodes, failingNodes, tips) =>
            TestFixture(
              (successfulNodes ++ failingNodes).toList,
              tips,
              memoizeNodes = memoize,
              sync = { (node, _) =>
                if (successfulNodes(node))
                  Task(true)
                else
                  Task.raiseError(new RuntimeException)
              },
              skipFailedNodesInNextRounds = true
            ) { (initialSynchronizer, mockGossipServiceServer) =>
              for {
                w <- initialSynchronizer.sync()
                // It's configured with minimum isccessful being infinite so it will try
                // forever and fail. We just want to give it enough time to try all of them.
                _ <- w.timeout(200.millis).attempt
              } yield {
                val asked = mockGossipServiceServer.asked.get()
                Inspectors.forAll(failingNodes) { node =>
                  asked.count(n => n == node) shouldBe 1
                }
                Inspectors.forAll(successfulNodes) { node =>
                  asked.count(n => n == node) should be >= 1
                }
              }
            }
        }

      """
        |invoke successful nodes multiple times and
        |not include failed nodes in next rounds
        |if memoization is true
      """.stripMargin in {
        test(memoize = true)
      }
      """
        |invoke successful nodes multiple times and
        |not include failed nodes in next rounds
        |if memoization is false
      """.stripMargin in {
        test(memoize = false)
      }

      "fails with error if all nodes responds with error" in forAll(genNodes(), genTips()) {
        (nodes, tips) =>
          TestFixture(
            nodes,
            tips,
            sync = (_, _) => Task.raiseError(new RuntimeException),
            skipFailedNodesInNextRounds = true
          ) { (initialSynchronizer, _) =>
            for {
              w <- initialSynchronizer.sync()
              r <- w.attempt
            } yield {
              r.isLeft shouldBe true
              r.left.get shouldBe an[SynchronizationError]
            }
          }
      }
    }

    "reaches minSuccessful amount of successful syncs" should {
      "resolve the handle" in forAll(genNodes(), genTips()) { (nodes, tips) =>
        val flag = Atomic(false)
        TestFixture(
          nodes,
          tips,
          sync = { (_, _) =>
            if (flag.get()) {
              Task(true)
            } else {
              Task.raiseError(new RuntimeException)
            }
          },
          minSuccessful = pos(nodes.size)
        ) { (initialSynchronizer, _) =>
          for {
            w1 <- initialSynchronizer.sync()
            r1 <- w1.timeout(75.millis).attempt
            _ <- Task {
                  r1.isLeft shouldBe true
                  r1.left.get shouldBe an[TimeoutException]
                }
            _ <- Task {
                  flag.set(true)
                }
            w2 <- initialSynchronizer.sync()
            r2 <- w2.attempt
            _ <- Task {
                  r2.isRight shouldBe true
                }
          } yield ()
        }
      }
    }
  }
}

object InitialSynchronizationSpec extends ArbitraryConsensus {
  implicit val logNoOp = new NOPLog[Task]
  implicit val metris  = new Metrics.MetricsNOP[Task]

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

    override def newBlocksSynchronous(
        request: NewBlocksRequest,
        skipRelaying: Boolean
    ): Task[NewBlocksResponse] = {
      asked.transform(_ :+ request.getSender)
      sync(request.getSender, request.blockHashes).map(b => NewBlocksResponse(isNew = b))
    }
  }

  class MockGossipService(tips: List[BlockSummary]) extends GossipService[Task] {
    def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] = ???
    def streamAncestorBlockSummaries(
        request: StreamAncestorBlockSummariesRequest
    ): Iterant[Task, BlockSummary] = ???
    def streamDagTipBlockSummaries(
        request: StreamDagTipBlockSummariesRequest
    ): Iterant[Task, BlockSummary] =
      Iterant.fromList[Task, BlockSummary](tips)
    def streamBlockSummaries(request: StreamBlockSummariesRequest): Iterant[Task, BlockSummary] =
      ???
    def getBlockChunked(request: GetBlockChunkedRequest): Iterant[Task, Chunk] = ???
    def getGenesisCandidate(request: GetGenesisCandidateRequest): Task[GenesisCandidate] =
      ???
    def addApproval(request: AddApprovalRequest): Task[Unit] = ???
  }

  object TestFixture {
    def apply(
        nodes: List[Node],
        tips: List[BlockSummary],
        sync: (Node, Seq[ByteString]) => Task[Boolean] = (_, _) => Task(true),
        selectNodes: (InitialSynchronizationImpl.Bootstrap, List[Node]) => List[Node] = (b, ns) =>
          (ns.toSet + b).toList,
        memoizeNodes: Boolean = false,
        minSuccessful: Int Refined Positive = Int.MaxValue,
        skipFailedNodesInNextRounds: Boolean = false,
        roundPeriod: FiniteDuration = 25.millis
    )(test: (InitialSynchronization[Task], MockGossipServiceServer) => Task[Unit]): Unit = {
      val mockGossipServiceServer                = new MockGossipServiceServer(sync)
      val mockGossipService: GossipService[Task] = new MockGossipService(tips)
      val effect = new InitialSynchronizationImpl(
        nodeDiscovery = new MockNodeDiscovery(nodes),
        mockGossipServiceServer,
        Bootstrap(nodes.head),
        selectNodes,
        memoizeNodes,
        _ => Task(mockGossipService),
        minSuccessful,
        skipFailedNodesInNextRounds,
        roundPeriod
      )
      test(effect, mockGossipServiceServer).runSyncUnsafe(5.seconds)
    }
  }
}
