package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.ServiceError, ServiceError.Unavailable
import io.casperlabs.shared.Log
import monix.eval.Task
import monix.execution.Scheduler
import monix.tail.Iterant
import org.scalatest._
import scala.concurrent.duration._
import scala.concurrent.TimeoutException
import org.scalacheck.Gen
import org.scalacheck.Arbitrary.arbitrary

class GenesisApproverSpec extends WordSpecLike with Matchers with ArbitraryConsensus {
  import GenesisApproverSpec._
  import Scheduler.Implicits.global

  "fromGenesis" when {
    "started with a candidate that passes the threshold" should {
      "immediately trigger the transition" in {
        TestFixture.fromGenesis() { approver =>
          // This would time out if it wasn't triggered already.
          approver.onApproved.map { blockHash =>
            blockHash shouldBe genesis.blockHash
          }
        }
      }
    }
    "started with a candidate that doesn't pass the threshold" should {
      "not trigger the transition" in {
        TestFixture.fromGenesis(
          environment = new MockEnvironment() {
            override def canTransition(block: Block, signatories: Set[ByteString]) = false
          }
        ) { approver =>
          approver.onApproved.timeout(50.millis).attempt.map { res =>
            res.isLeft shouldBe true
            res.left.get shouldBe a[TimeoutException]
          }
        }
      }
    }
  }
  "fromGenesis" should {
    "create the candidate with the single approval" in {
      TestFixture.fromGenesis() { approver =>
        approver.getCandidate.map { res =>
          res.isRight shouldBe true
          res.right.get.blockHash shouldBe genesis.blockHash
          res.right.get.approvals should have size 1
        }
      }
    }
  }

  "fromBootstrap" should {
    "return UNAVAILABLE while there is no candidate" in {
      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.raiseError(Unavailable("No candidate here."))
      ) { approver =>
        approver.getCandidate map {
          case Left(Unavailable(msg)) =>
            msg should include("The Genesis candidate is not yet available.")
          case other => fail(s"Expected Unavailable; got $other")
        }
      }
    }

    "return the candidate once its established" in {
      TestFixture.fromBootstrap(
        remoteCandidate =
          () => Task.now(GenesisCandidate(genesis.blockHash)).delayResult(100.millis),
        pollInterval = 10.millis
      ) { approver =>
        for {
          r0 <- approver.getCandidate
          _  <- Task.sleep(200.millis)
          r1 <- approver.getCandidate
        } yield {
          r0.isLeft shouldBe true
          r1.isRight shouldBe true
          r1.right.get.blockHash shouldBe genesis.blockHash
        }
      }
    }

    "keep polling until the transition can be made" in {
      @volatile var candidate = GenesisCandidate(genesis.blockHash)

      def addApproval() = Task.delay {
        val n = candidate.approvals.size
        val a = genesis.getHeader.getState.bonds.drop(n).take(1).map { b =>
          Approval(b.validatorPublicKey).withSignature(sample(arbitrary[Signature]))
        }
        candidate = candidate.copy(approvals = candidate.approvals ++ a)
      }

      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.delay(candidate),
        environment = new MockEnvironment() {
          override def canTransition(block: Block, signatories: Set[ByteString]): Boolean =
            signatories.size > 1
        },
        pollInterval = 10.millis
      ) { approver =>
        val ops = List.fill(5) {
          for {
            _ <- Task.sleep(50.millis)
            r <- approver.getCandidate
            _ <- addApproval()
          } yield r.right.get.approvals.size
        }
        ops.sequence.map {
          _ shouldBe List(0, 1, 2, 2, 2)
        }
      }
    }

    "not stop polling if there's an error" in {
      @volatile var exploded = false
      TestFixture.fromBootstrap(
        remoteCandidate = () =>
          if (!exploded) {
            exploded = true
            Task.raiseError(new RuntimeException("Bang!"))
          } else {
            Task.now(GenesisCandidate(genesis.blockHash))
          },
        pollInterval = 10.millis
      ) { approver =>
        for {
          _ <- Task.sleep(100.millis)
          r <- approver.getCandidate
        } yield {
          r.isRight shouldBe true
        }
      }
    }

    "not accept invalid approvals" in {
      val approvals = Seq(
        // Completely unrelated.
        sample(arbitrary[Approval]),
        // Wrong signature.
        sample(arbitrary[Approval])
          .withSignature(sample(arbitrary[Signature]).withSigAlgorithm("XXX")),
        // Good one.
        sample(arbitrary[Approval])
          .copy(validatorPublicKey = genesis.getHeader.getState.bonds.head.validatorPublicKey)
      )
      TestFixture.fromBootstrap(
        remoteCandidate = () =>
          Task.delay {
            GenesisCandidate(genesis.blockHash).withApprovals(approvals)
          },
        environment = new MockEnvironment() {
          override def validateSignature(
              blockHash: ByteString,
              publicKey: ByteString,
              signature: Signature
          ) = signature.sigAlgorithm != "XXX"
        }
      ) { approver =>
        for {
          _ <- Task.sleep(50.millis)
          r <- approver.getCandidate
        } yield {
          r.right.get.approvals should have size 1
        }
      }
    }

    "not accept changes in the candidate" in {
      @volatile var candidate = GenesisCandidate(genesis.blockHash)

      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.delay(candidate),
        pollInterval = 10.millis,
        environment = new MockEnvironment() {
          override def canTransition(block: Block, signatories: Set[ByteString]) = false
        }
      ) { approver =>
        for {
          _  <- Task.sleep(50.millis)
          r0 <- approver.getCandidate
          _  <- Task.delay { candidate = GenesisCandidate(sample(genHash)) }
          _  <- Task.sleep(50.millis)
          r1 <- approver.getCandidate
        } yield {
          r0 shouldBe r1
        }
      }
    }

    "not accept an invalid candidate" in {
      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.delay(GenesisCandidate(genesis.blockHash)),
        environment = new MockEnvironment() {
          override def validateCandidate(block: Block) =
            Task.raiseError(InvalidArgument("I don't like this at all."))
        }
      ) { approver =>
        for {
          _ <- Task.sleep(50.millis)
          r <- approver.getCandidate
        } yield {
          r.isLeft shouldBe true
          r.left.get.getMessage should include("UNAVAILABLE")
        }
      }
    }
  }

  "addApproval" should {
    "accumulate correct approvals" in (pending)
    "reject incorrect approvals" in (pending)
    "return UNAVAILABLE while there is no candidate" in (pending)
    "return true once the threshold has been passed" in (pending)
    "gossip new approvals" in (pending)
    "not gossip old approvals" in (pending)
    "not stop gossiping if there's an error" in (pending)
    "trigger onApproved when the threshold is passed" in (pending)
  }
}

object GenesisApproverSpec extends ArbitraryConsensus {
  //implicit val noLog           = new Log.NOPLog[Task]
  implicit val log             = Log.log[Task]
  implicit val consensusConfig = ConsensusConfig()

  val peers = sample {
    Gen.listOfN(10, arbitrary[Node])
  }

  val bootstrap = peers.head

  val genesis = {
    val block = sample(arbitrary[Block])
    val bonds = sample(Gen.listOfN(5, arbitrary[Bond]))
    val state = block.getHeader.getState.withBonds(bonds)
    block.withHeader(block.getHeader.withState(state))
  }

  class MockNodeDiscovery(peers: List[Node]) extends NodeDiscovery[Task] {
    override def discover                    = ???
    override def lookup(id: NodeIdentifier)  = ???
    override def alivePeersAscendingDistance = Task.now(peers)
  }

  object MockGossipService {
    trait Base extends GossipService[Task] {
      override def newBlocks(request: NewBlocksRequest)                                       = ???
      override def streamAncestorBlockSummaries(request: StreamAncestorBlockSummariesRequest) = ???
      override def streamDagTipBlockSummaries(request: StreamDagTipBlockSummariesRequest)     = ???
      override def streamBlockSummaries(
          request: StreamBlockSummariesRequest
      ): Iterant[Task, BlockSummary]                                = ???
      override def getBlockChunked(request: GetBlockChunkedRequest) = ???
      override def addApproval(request: AddApprovalRequest)         = ???
      override def getGenesisCandidate(
          request: GetGenesisCandidateRequest
      ): Task[GenesisCandidate] = ???
    }

    class Bootstrap(getCandidate: () => Task[GenesisCandidate]) extends Base {
      override def getGenesisCandidate(request: GetGenesisCandidateRequest) =
        getCandidate()

      override def streamBlockSummaries(request: StreamBlockSummariesRequest) =
        request.blockHashes match {
          case Seq(genesis.blockHash) =>
            Iterant.pure(BlockSummary(genesis.blockHash).withHeader(genesis.getHeader))
          case _ => ???
        }
    }

    class NonBootstrap() extends Base {}
  }

  // Default test environment which accepts anything and pretends to download a block.
  class MockEnvironment() extends GenesisApproverImpl.Backend[Task] with DownloadManager[Task] {

    @volatile var downloaded = false

    override def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean) =
      Task.delay {
        downloaded = true
        Task.unit
      }
    override def validateCandidate(block: Block)                           = Task.now(Right(()))
    override def canTransition(block: Block, signatories: Set[ByteString]) = true
    override def validateSignature(
        blockHash: ByteString,
        publicKey: ByteString,
        signature: Signature
    ) = true
    override def getBlock(blockHash: ByteString) =
      if (downloaded) Task.now(Some(genesis)) else Task.now(None)
  }

  object TestFixture {
    val DefaultTimeout = 5.seconds

    def fromBootstrap(
        remoteCandidate: () => Task[GenesisCandidate],
        environment: MockEnvironment = new MockEnvironment(),
        relayFactor: Int = 0,
        pollInterval: FiniteDuration = 100.millis
    )(
        test: GenesisApprover[Task] => Task[Unit]
    )(
        implicit scheduler: Scheduler
    ): Unit =
      GenesisApproverImpl
        .fromBootstrap[Task](
          backend = environment,
          new MockNodeDiscovery(peers),
          connectToGossip = (node: Node) =>
            Task.now {
              if (node == bootstrap)
                new MockGossipService.Bootstrap(remoteCandidate)
              else
                new MockGossipService.NonBootstrap()
            },
          relayFactor,
          bootstrap,
          pollInterval,
          downloadManager = environment
        )
        .use(test)
        .runSyncUnsafe(DefaultTimeout)

    def fromGenesis(
        environment: MockEnvironment = new MockEnvironment(),
        relayFactor: Int = 0
    )(
        test: GenesisApprover[Task] => Task[Unit]
    )(
        implicit scheduler: Scheduler
    ): Unit =
      GenesisApproverImpl
        .fromGenesis[Task](
          backend = environment,
          new MockNodeDiscovery(peers),
          connectToGossip = (node: Node) => Task.now(new MockGossipService.NonBootstrap()),
          relayFactor,
          genesis,
          sample(arbitrary[Approval])
            .withValidatorPublicKey(genesis.getHeader.getState.bonds.head.validatorPublicKey)
        )
        .use(test)
        .runSyncUnsafe(DefaultTimeout)
  }
}
