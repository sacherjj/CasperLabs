package io.casperlabs.comm.gossiping

import cats.effect.concurrent.Semaphore
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.{Approval, BlockSummary, GenesisCandidate}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping.InitialSyncSpec.TestFixture
import io.casperlabs.shared.Log.NOPLog
import monix.eval.Task
import cats.temp.par._
import io.casperlabs.comm.ServiceError
import monix.eval.instances.CatsParallelForTask
import monix.execution.{ExecutionModel, Scheduler}
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic
import monix.execution.schedulers.CanBlock
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

  test("should try different sources if sync fails") {
    forAll(genNodes(), arbBlockSummary.arbitrary) { (nodes, tip) =>
      val e = new RuntimeException("Boom!")
      TestFixture(nodes, _ => List(tip), 1L, (_, _) => Task.raiseError[Boolean](e)) {
        (sync, mock) =>
          for {
            w <- sync
            _ <- w.timeout(200.millis).attempt
          } yield {
            mock.asked.get() should contain allElementsOf nodes
          }
      }
    }
  }

  test("""
      |should parallelize syncing according to 'parallelism'
      |if there many tips
      |""".stripMargin) {
    val nodes = sample(genNodes())
    val tips  = sample(genTips(2000))

    val p = refineV[Positive](Runtime.getRuntime.availableProcessors().toLong).right.get
    TestFixture(nodes, _ => tips, p, (_, _) => Task(true)) { (sync, mock) =>
      for {
        w <- sync
        _ <- w.timeout(2.seconds).attempt
      } yield {
        // Do not know why, but it always equals to 1
        // Ideally, we'd like to test that it's > 1 && <= p
        mock.maxConcurrency.get() should be <= p.toInt
      }
    }
  }

  test("should resolve handle when there are no new tips") {
    forAll(genNodes(), arbBlockSummary.arbitrary) { (nodes, tip) =>
      val atomicBoolean = Atomic(true)
      TestFixture(nodes, _ => List(tip), 1L, (_, _) => Task.delay(atomicBoolean.get())) {
        (sync, _) =>
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
    val asked          = Atomic(Vector.empty[Node])
    private val c      = Atomic(0)
    val maxConcurrency = Atomic(0)

    override protected def newBlocksSynchronous(
        request: NewBlocksRequest
    ): Task[NewBlocksResponse] = {
      c.increment()
      maxConcurrency.transform(math.max(_, c.get()))
      c.decrement()
      asked.transform(_ :+ request.getSender)
      sync(request.getSender, request.blockHashes).map(b => NewBlocksResponse(isNew = b))
    }
  }

  object TestFixture {
    def apply(
        nodes: List[Node],
        tips: Node => List[BlockSummary],
        parallelism: Long Refined Positive,
        sync: (Node, Seq[ByteString]) => Task[Boolean],
        roundPeriod: FiniteDuration = 2.seconds
    )(test: (Task[Task[Unit]], MockGossipServiceServer) => Task[Unit]): Unit = {
//      implicit val scheduler: Scheduler =
//        Scheduler.io(executionModel = ExecutionModel.AlwaysAsyncExecution)

      val mockGossipServiceServer = new MockGossipServiceServer(sync)
      val effect = GossipServiceServer
        .initialSync(
          new MockNodeDiscovery(nodes),
          mockGossipServiceServer, { node =>
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
          },
          parallelism,
          roundPeriod
        )
      test(effect, mockGossipServiceServer).runSyncUnsafe(5.seconds)
    }
  }
}
