package io.casperlabs.comm.gossiping

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.ServiceError, ServiceError.{InvalidArgument, Unavailable}
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
          approver.awaitApproval.timeout(500.millis).map { blockHash =>
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
          approver.awaitApproval.timeout(50.millis).attempt.map { res =>
            res.isLeft shouldBe true
            res.left.get shouldBe a[TimeoutException]
          }
        }
      }
    }
    "started without an approval" should {
      "not trigger the transition if approvals are needed" in {
        TestFixture.fromGenesis(approve = false, environment = new MockEnvironment() {
          override def canTransition(block: Block, signatories: Set[ByteString]) = false
        }) { approver =>
          approver.awaitApproval.timeout(50.millis).attempt.map { res =>
            res.isLeft shouldBe true
            res.left.get shouldBe a[TimeoutException]
          }
        }
      }

      "trigger the transition if approvals are not needed" in {
        TestFixture.fromGenesis(approve = false) { approver =>
          approver.awaitApproval.timeout(50.millis).attempt.map { res =>
            res.isRight shouldBe true
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
          _  <- Task.sleep(500.millis)
          r1 <- approver.getCandidate
        } yield {
          r0.isLeft shouldBe true
          r1.isRight shouldBe true
          r1.right.get.blockHash shouldBe genesis.blockHash
        }
      }
    }

    "keep polling until the transition can be made" in {
      // We're going to keep adding approvals to the candidate from the test.
      @volatile var candidate = GenesisCandidate(genesis.blockHash)

      def addApproval() = Task.delay {
        val n = candidate.approvals.size
        // The next validator signs it, so it's a valid signature.
        val a = genesis.getHeader.getState.bonds.drop(n).take(1).map { b =>
          Approval(b.validatorPublicKey).withSignature(sample(arbitrary[Signature]))
        }
        candidate = candidate.copy(approvals = candidate.approvals ++ a)
      }

      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.delay(candidate),
        environment = new MockEnvironment() {
          // Need 2 signatures.
          override def canTransition(block: Block, signatories: Set[ByteString]): Boolean =
            signatories.size > 1
        },
        pollInterval = 10.millis
      ) { approver =>
        val ops = List.fill(4) {
          for {
            _ <- Task.sleep(200.millis)
            // Ask the approver itself what it thinks the candidate is.
            // We keep adding approvals here but it should top at approvals.
            r <- approver.getCandidate
            _ <- addApproval()
          } yield r.right.get.approvals.size
        }
        ops.sequence.map {
          _ shouldBe List(0, 1, 2, 2)
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
          .withApproverPublicKey(genesis.getHeader.getState.bonds.head.validatorPublicKey)
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

    "gossip own approval for a valid candidate" in {
      val service = new MockGossipService.GossipCounter()

      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.delay(GenesisCandidate(genesis.blockHash)),
        environment = new MockEnvironment() {
          override def validateCandidate(block: Block) =
            Task.delay(Right(correctApproval.some))
        },
        gossipService = service,
        relayFactor = 5
      ) { approver =>
        for {
          _ <- Task.sleep(500.millis)
          r <- approver.getCandidate
        } yield {
          r.isRight shouldBe true
          service.received shouldBe 5
        }
      }
    }

    "trigger transition if no approvals are required" in {
      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.delay(GenesisCandidate(genesis.blockHash, approvals = Nil)),
        pollInterval = 10.millis,
        environment = new MockEnvironment() {
          override def canTransition(block: Block, signatories: Set[ByteString]) = true
        }
      ) { approver =>
        approver.awaitApproval.timeout(250.millis).map { blockHash =>
          blockHash shouldBe genesis.blockHash
        }
      }
    }
  }

  "addApproval" should {
    "accumulate correct approvals" in {
      TestFixture.fromGenesis() { approver =>
        for {
          _ <- approver.addApproval(genesis.blockHash, correctApproval)
          c <- approver.getCandidate
        } yield {
          c.right.get.approvals should have size 2
        }
      }
    }

    "reject approvals for a different candidate" in {
      TestFixture.fromGenesis() { approver =>
        approver.addApproval(sample(genHash), correctApproval) map {
          case Left(InvalidArgument(msg)) =>
            msg should include("block hash doesn't match")
          case other =>
            fail(s"Expected InvalidArgument; got $other")
        }
      }
    }

    "reject incorrect validators" in {
      TestFixture.fromGenesis() { approver =>
        approver.addApproval(genesis.blockHash, sample(arbitrary[Approval])) map {
          case Left(InvalidArgument(msg)) =>
            msg should include("not one of the bonded validators")
          case other =>
            fail(s"Expected InvalidArgument; got $other")
        }
      }
    }

    "reject incorrect signatures" in {
      TestFixture.fromGenesis(
        environment = new MockEnvironment() {
          override def validateSignature(
              blockHash: ByteString,
              publicKey: ByteString,
              signature: Signature
          ) = false
        }
      ) { approver =>
        approver.addApproval(genesis.blockHash, correctApproval) map {
          case Left(InvalidArgument(msg)) =>
            msg should include("signature")
          case other =>
            fail(s"Expected InvalidArgument; got $other")
        }
      }
    }

    "return UNAVAILABLE while there is no candidate" in {
      TestFixture.fromBootstrap(
        remoteCandidate = () => Task.raiseError(Unavailable("Thinking..."))
      ) { approver =>
        approver.addApproval(genesis.blockHash, sample(arbitrary[Approval])).map {
          case Left(Unavailable(msg)) =>
            msg should include("not yet available")
          case other =>
            fail(s"Expected Unavailable; got $other")
        }
      }
    }

    "return true once the threshold has been passed" in {
      TestFixture.fromGenesis(
        environment = new MockEnvironment() {
          override def canTransition(block: Block, signatories: Set[ByteString]) =
            signatories.size >= 3
        }
      ) { approver =>
        for {
          r1 <- approver.addApproval(genesis.blockHash, correctApproval)
          r2 <- approver.addApproval(
                 genesis.blockHash,
                 correctApproval.withApproverPublicKey(
                   genesis.getHeader.getState.bonds(1).validatorPublicKey
                 )
               )
        } yield {
          r1.right.get shouldBe false
          r2.right.get shouldBe true
        }
      }
    }

    "accumulate approvals arriving in parallel" in {
      TestFixture.fromGenesis() { approver =>
        val approvals = genesis.getHeader.getState.bonds.tail.map { b =>
          sample(arbitrary[Approval]).withApproverPublicKey(b.validatorPublicKey)
        }
        for {
          _ <- Task.gatherUnordered(approvals.map(approver.addApproval(genesis.blockHash, _)))
          c <- approver.getCandidate
        } yield {
          c.right.get.approvals should have size (1L + approvals.size)
        }
      }
    }

    "gossip new approvals" in {
      val service = new MockGossipService.GossipCounter()

      TestFixture.fromGenesis(
        relayFactor = 2,
        gossipService = service
      ) { approver =>
        for {
          _ <- Task.sleep(100.millis)
          _ = service.received shouldBe 2
          _ <- approver.addApproval(genesis.blockHash, correctApproval)
          _ <- Task.sleep(100.millis)
          _ = service.received shouldBe 4
        } yield ()
      }
    }

    "not gossip old approvals" in {
      val service = new MockGossipService.GossipCounter()

      TestFixture.fromGenesis(
        relayFactor = 2,
        gossipService = service
      ) { approver =>
        for {
          _ <- Task.sleep(100.millis)
          _ = service.received shouldBe 2
          c <- approver.getCandidate
          _ <- approver.addApproval(genesis.blockHash, c.right.get.approvals.head)
          _ <- Task.sleep(100.millis)
          _ = service.received shouldBe 2
        } yield ()
      }
    }

    "not stop gossiping if there's an error" in {
      val service = new MockGossipService.GossipCounter() {
        override def addApproval(request: AddApprovalRequest) =
          super.addApproval(request).flatMap { res =>
            if (received == 1) {
              Task.raiseError(Unavailable("The first peer wasn't ready!"))
            } else {
              Task.now(res)
            }
          }
      }

      TestFixture.fromGenesis(
        relayFactor = 3,
        gossipService = service
      ) { approver =>
        for {
          _ <- Task.sleep(100.millis)
          _ = service.received shouldBe (1 + 3)
        } yield ()
      }
    }

    "trigger awaitApproval when the threshold is passed" in {
      TestFixture.fromGenesis(
        environment = new MockEnvironment() {
          override def canTransition(block: Block, signatories: Set[ByteString]) =
            signatories.size > 1
        }
      ) { approver =>
        for {
          r0 <- approver.awaitApproval.timeout(Duration.Zero).attempt
          _  = r0.isLeft shouldBe true
          _  <- approver.addApproval(genesis.blockHash, correctApproval)
          r1 <- approver.awaitApproval
        } yield ()
      }
    }
  }
}

object GenesisApproverSpec extends ArbitraryConsensus {
  implicit val noLog           = new Log.NOPLog[Task]
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

  // Example of an approval that we can use in tests that won't be rejected.
  val correctApproval = sample(arbitrary[Approval])
    .withApproverPublicKey(genesis.getHeader.getState.bonds.last.validatorPublicKey)

  class MockNodeDiscovery(peers: List[Node]) extends NodeDiscovery[Task] {
    override def discover                    = ???
    override def lookup(id: NodeIdentifier)  = ???
    override def alivePeersAscendingDistance = Task.now(peers)
  }

  class MockGossipService extends GossipService[Task] {
    override def newBlocks(request: NewBlocksRequest)                                       = ???
    override def streamAncestorBlockSummaries(request: StreamAncestorBlockSummariesRequest) = ???
    override def streamDagTipBlockSummaries(request: StreamDagTipBlockSummariesRequest)     = ???
    override def streamBlockSummaries(
        request: StreamBlockSummariesRequest
    ): Iterant[Task, BlockSummary]                                    = ???
    override def getBlockChunked(request: GetBlockChunkedRequest)     = ???
    override def addApproval(request: AddApprovalRequest): Task[Unit] = ???
    override def getGenesisCandidate(
        request: GetGenesisCandidateRequest
    ): Task[GenesisCandidate] = ???
  }
  object MockGossipService {
    class Bootstrap(getCandidate: () => Task[GenesisCandidate]) extends MockGossipService() {
      override def getGenesisCandidate(request: GetGenesisCandidateRequest) =
        getCandidate()

      override def streamBlockSummaries(request: StreamBlockSummariesRequest) =
        request.blockHashes match {
          case Seq(genesis.blockHash) =>
            Iterant.pure(BlockSummary(genesis.blockHash).withHeader(genesis.getHeader))
          case _ => ???
        }
    }

    class GossipCounter() extends MockGossipService() {
      @volatile var received = 0
      override def addApproval(request: AddApprovalRequest) = Task.delay {
        synchronized {
          received = received + 1
        }
      }
    }
  }

  // Default test environment which accepts anything and pretends to download a block.
  class MockEnvironment() extends GenesisApproverImpl.Backend[Task] with DownloadManager[Task] {

    @volatile var downloaded = false

    override def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean) =
      Task.delay {
        downloaded = true
        Task.unit
      }
    override def validateCandidate(block: Block)                           = Task.now(Right(none[Approval]))
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
        gossipService: MockGossipService = new MockGossipService(),
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
                gossipService
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
        gossipService: MockGossipService = new MockGossipService(),
        relayFactor: Int = 0,
        approve: Boolean = true
    )(
        test: GenesisApprover[Task] => Task[Unit]
    )(
        implicit scheduler: Scheduler
    ): Unit =
      GenesisApproverImpl
        .fromGenesis[Task](
          backend = environment,
          new MockNodeDiscovery(peers),
          connectToGossip = _ => Task.now(gossipService),
          relayFactor,
          genesis,
          maybeApproval = sample(arbitrary[Approval])
            .withApproverPublicKey(genesis.getHeader.getState.bonds.head.validatorPublicKey)
            .some
            .filter(_ => approve)
        )
        .use(test)
        .runSyncUnsafe(DefaultTimeout)
  }
}
