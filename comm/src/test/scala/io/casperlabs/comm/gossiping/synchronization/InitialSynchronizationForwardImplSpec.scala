package io.casperlabs.comm.gossiping.synchronization

import java.util.concurrent.TimeoutException

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.synchronization.InitialSynchronization.SynchronizationError
import io.casperlabs.comm.gossiping.synchronization.InitialSynchronizationForwardImplSpec.TestFixture
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.shared.Log
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.{Atomic, AtomicInt}
import monix.tail.Iterant
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Inspectors, Matchers, WordSpecLike}

import scala.concurrent.duration._

class InitialSynchronizationForwardImplSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensusAndComm
    with GeneratorDrivenPropertyChecks {
  private implicit val noShrinkNodes: Shrink[List[Node]] = Shrink(_ => Stream.empty)

  private implicit val chainId: ByteString = sample(genHash)

  private implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    dagSize = 10,
    maxSessionCodeBytes = 1,
    maxPaymentCodeBytes = 1
  )

  private def genNodes(min: Int = 1, max: Int = 10) =
    Gen.choose(min, max).flatMap(n => Gen.listOfN(n, arbNode.arbitrary))

  private def genDag() = genSummaryDagFromGenesis

  "InitialSynchronization" when {
    "doesn't have nodes in the initial round" should {
      "try again later" in forAll(genNodes(), genDag()) { (nodes, dag) =>
        val counter = AtomicInt(0)
        TestFixture(
          nodes,
          Task(dag),
          selectNodes = { nodes =>
            val cnt = counter.incrementAndGet()
            if (cnt == 1) Nil else nodes
          },
          minSuccessful = 1
        ) { (initialSynchronizer, _) =>
          for {
            w <- initialSynchronizer.sync()
            _ <- w.timeout(1.second)
          } yield {
            counter.get() shouldBe 2
          }
        }
      }
    }
    "specified to memoize nodes between rounds" should {
      def test(skipFailedNodesInNextRound: Boolean): Unit = forAll(genNodes()) { nodes =>
        val counter = Atomic(0)
        TestFixture(
          nodes,
          Task.raiseError(new RuntimeException("Boom!")),
          memoizeNodes = true,
          selectNodes = { nodes =>
            counter.increment()
            nodes
          },
          minSuccessful = nodes.size,
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
      def test(memoize: Boolean): Unit = {
        val successfulNodes = sample(genNodes())
        val failingNodes    = sample(genNodes()).toSet
        val dag             = sample(genDag())

        TestFixture(
          successfulNodes ++ failingNodes,
          Task(dag),
          memoizeNodes = memoize,
          failing = failingNodes,
          skipFailedNodesInNextRounds = true
        ) { (initialSynchronizer, mockDownloadManager) =>
          for {
            w <- initialSynchronizer.sync()
            // It's configured with minimum successful being infinite so it will try
            // forever and fail. We just want to give it enough time to try all of them.
            _ <- w.timeout(200.millis).attempt
          } yield {
            val asked = mockDownloadManager.requestsCounter.get()
            Inspectors.forAll(failingNodes) { node =>
              asked(node) shouldBe dag.size
            }
            Inspectors.forAll(successfulNodes) { node =>
              asked(node) should be >= dag.size
            }
          }
        }
      }

      """|invoke successful nodes multiple times and
         |not include failed nodes in next rounds
         |if memoization is true
      """.stripMargin in {
        test(memoize = true)
      }
      """|invoke successful nodes multiple times and
         |not include failed nodes in next rounds
         |if memoization is false
      """.stripMargin in {
        test(memoize = false)
      }

      "fails with error if all nodes responds with error" in forAll(genNodes()) { nodes =>
        TestFixture(
          nodes,
          Task.raiseError(new RuntimeException("Boom!")),
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
      "resolve the handle" in forAll(genNodes(), genDag()) { (nodes, dag) =>
        val flag = Atomic(false)
        TestFixture(
          nodes,
          Task.defer {
            if (flag.get()) {
              Task(dag)
            } else {
              Task.raiseError(new RuntimeException("Boom!"))
            }
          },
          minSuccessful = nodes.size
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
    "returned dag slice contains summaries with unasked rank" should {
      "mark node as failed" in {
        val nodes = sample(genNodes(max = 1))
        val dag = {
          val d = sample(genDag())
          d.head.update(_.header.rank := 100) +: d.tail
        }
        TestFixture(
          nodes,
          Task(dag),
          correctRanges = false,
          skipFailedNodesInNextRounds = true
        ) { (initialSynchronizer, _) =>
          for {
            w <- initialSynchronizer.sync()
            r <- w.attempt
          } yield {
            r.left.get shouldBe an[SynchronizationError]
          }
        }
      }
    }
    "returned dag slice contains repeated summaries" should {
      "consider sync with such peers as failure" in {
        val nodes = sample(genNodes(max = 1))
        val dag = {
          val d = sample(genDag()).head
          Vector(d, d)
        }
        TestFixture(
          nodes,
          Task(dag),
          skipFailedNodesInNextRounds = true
        ) { (initialSynchronizer, _) =>
          for {
            w <- initialSynchronizer.sync()
            r <- w.attempt
          } yield {
            r.left.get shouldBe an[SynchronizationError]
          }
        }
      }
    }
  }
}

object InitialSynchronizationForwardImplSpec extends ArbitraryConsensus {
  implicit val logNoOp = Log.NOPLog[Task]
  implicit val metris  = new Metrics.MetricsNOP[Task]

  class MockNodeDiscovery(nodes: List[Node]) extends NodeDiscovery[Task] {
    def discover                            = ???
    def lookup(id: NodeIdentifier)          = ???
    def recentlyAlivePeersAscendingDistance = Task.now(nodes)
    def banTemp(node: Node): Task[Unit]     = ???
  }

  class MockDownloadManager(failing: Set[Node]) extends DownloadManager[Task] {
    val requestsCounter = Atomic(Map.empty[Node, Int].withDefaultValue(0))

    def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean) =
      Task.delay {
        requestsCounter.transform(m => m + (source -> (m(source) + 1)))
        if (failing(source)) {
          Task.raiseError(new RuntimeException("Boom!"))
        } else {
          Task.unit
        }
      }

  }

  class MockGossipService(produceDag: Task[Vector[BlockSummary]], correct: Boolean)
      extends GossipService[Task] {
    def newBlocks(request: NewBlocksRequest)                                       = ???
    def streamAncestorBlockSummaries(request: StreamAncestorBlockSummariesRequest) = ???
    def streamLatestMessages(request: StreamLatestMessagesRequest)                 = ???
    def streamBlockSummaries(request: StreamBlockSummariesRequest)                 = ???
    def getBlockChunked(request: GetBlockChunkedRequest)                           = ???
    def getGenesisCandidate(request: GetGenesisCandidateRequest)                   = ???
    def addApproval(request: AddApprovalRequest)                                   = ???
    def streamDagSliceBlockSummaries(request: StreamDagSliceBlockSummariesRequest) =
      Iterant
        .liftF {
          produceDag.flatMap { dag =>
            Task {
              val range = request.startRank.to(request.endRank)
              if (correct) {
                dag.filter(s => range.contains(s.rank))
              } else {
                dag
              }
            }
          }
        }
        .flatMap(summaries => Iterant.fromSeq[Task, BlockSummary](summaries))
  }

  object TestFixture {
    def apply(
        nodes: List[Node],
        produceDag: Task[Vector[BlockSummary]],
        correctRanges: Boolean = true,
        failing: Set[Node] = Set.empty,
        selectNodes: List[Node] => List[Node] = _.distinct,
        memoizeNodes: Boolean = false,
        minSuccessful: Int = Int.MaxValue,
        skipFailedNodesInNextRounds: Boolean = false,
        step: Int = 10,
        rankStartFrom: Long = 0L,
        roundPeriod: FiniteDuration = Duration.Zero
    )(
        test: (InitialSynchronization[Task], MockDownloadManager) => Task[Unit]
    ): Unit = {
      val mockGossipService   = new MockGossipService(produceDag, correctRanges)
      val mockDownloadManager = new MockDownloadManager(failing)
      val effect = new InitialSynchronizationForwardImpl[Task](
        nodeDiscovery = new MockNodeDiscovery(nodes),
        selectNodes,
        memoizeNodes,
        _ => Task(mockGossipService),
        minSuccessful,
        skipFailedNodesInNextRounds,
        mockDownloadManager,
        step,
        rankStartFrom,
        roundPeriod
      )
      test(effect, mockDownloadManager).runSyncUnsafe(5.seconds)
    }
  }
}
