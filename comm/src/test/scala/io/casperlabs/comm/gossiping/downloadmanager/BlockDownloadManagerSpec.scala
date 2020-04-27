package io.casperlabs.comm.gossiping.downloadmanager

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined.auto._
import io.casperlabs.casper.consensus.{Block, BlockSummary, Deploy}
import io.casperlabs.comm.GossipError
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.downloadmanager.BlockDownloadManagerImpl._
import io.casperlabs.comm.gossiping.relaying.BlockRelaying
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits.BlockOps
import io.casperlabs.shared.{Log, LogStub}
import monix.eval.Task
import monix.execution.Scheduler
import monix.tail.Iterant
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest._

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

/**
  * TODO: Uses [[BlockDownloadManagerImpl]] for testing.
  * It's ok for now, because the most of the code shared between [[DeployDownloadManagerImpl]].
  * However, if it's going to change, then we'll need to revisit this.
  */
class BlockDownloadManagerSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensusAndComm {

  import BlockDownloadManagerSpec._
  import Scheduler.Implicits.global

  // Collect log messages. Reset before each test.
  implicit val log = LogStub[Task]()

  override def beforeEach() =
    log.reset()

  implicit val chainId: ByteString = sample(genHash)

  // Create a random Node so I don't have to repeat this in all tests.
  val source = sample(arbitrary[Node])

  // Don't have to create big blocks because it takes ages.
  implicit val consensusConfig =
    ConsensusConfig(dagSize = 10, maxSessionCodeBytes = 50, maxPaymentCodeBytes = 10)

  "DownloadManager" when {
    "scheduled to download a section of the DAG" should {
      // Make sure the genesis has more than 1 child so we can download them in parallel. This is easy to check.
      val dag = sample(
        genBlockDagFromGenesis
          .retryUntil { blocks =>
            (blocks(1).getHeader.parentHashes.toSet & blocks(2).getHeader.parentHashes.toSet).nonEmpty
          }
      )
      val relayed = sample(for {
        n  <- Gen.choose(1, dag.size)
        bs <- Gen.pick(n, dag)
      } yield bs.toSet)
      val blockMap = toBlockMap(dag)
      val remote   = MockGossipService(dag)
      val relaying = MockRelaying.default

      def scheduleAll(manager: BlockDownloadManager[Task]): Task[List[Task[Unit]]] =
        // Add them in the natural topological order they were generated with.
        dag.toList.traverse { block =>
          manager.scheduleDownload(summaryOf(block), source, relay = relayed(block))
        }

      def awaitAll(watches: List[Task[Unit]]): Task[Unit] =
        watches.traverse(identity).void

      "eventually download the full DAG" in TestFixture(remote = _ => remote) {
        case (manager, backend) =>
          if (sys.env.contains("DRONE_BRANCH")) {
            cancel("NODE-1152")
          }
          for {
            ws <- scheduleAll(manager)
            _  = backend.scheduled should contain theSameElementsAs dag.map(_.blockHash)
            _  <- awaitAll(ws)
          } yield {
            if (sys.env.contains("DRONE_BRANCH")) {
              cancel("NODE-1089")
            }
            backend.blocks should contain theSameElementsAs dag.map(_.blockHash)
          }
      }

      "download blocks in topological order" in TestFixture(
        remote = _ =>
          MockGossipService(
            dag,
            // Delay just the genesis block a little so we can schedule everything before the first download finishes.
            alterBlock = _ flatMap {
              case Some(block) if block == dag.head =>
                Task.pure(Some(block)).delayResult(500.millis)
              case other => Task.pure(other)
            }
          ),
        maxParallelDownloads = consensusConfig.dagSize
      ) {
        case (manager, backend) =>
          for {
            watches <- scheduleAll(manager)
            _       = backend.blocks shouldBe empty // So we know it hasn't done them immedately during the scheduling.
            _       <- awaitAll(watches)
          } yield {
            // All parents should be stored before their children.
            Inspectors.forAll(backend.blocks.zipWithIndex) {
              case (blockHash, idx) =>
                val followed = backend.blocks.drop(idx + 1).toSet
                val deps     = dependenciesOf(blockMap(blockHash)).toSet
                followed intersect deps shouldBe empty
            }
          }
      }

      "only download missing deploys" in {
        // Say we already have the first deploy of each block.
        val deployMap: Map[ByteString, Deploy] = (for {
          block  <- dag
          deploy <- block.getBody.deploys.take(1).map(_.getDeploy)
        } yield (deploy.deployHash -> deploy)).toMap

        @volatile var fetchedDeploys = Set.empty[ByteString]

        TestFixture(
          backend = new MockBackend() {
            override def readDeploys(deployHashes: Seq[ByteString]) = Task.delay {
              deployHashes.flatMap(deployMap.get).toList
            }
          },
          remote = _ =>
            MockGossipService(
              dag,
              interceptGetBlock = (_, deployBodiesExcluded) =>
                assert(deployBodiesExcluded, "Should not ask for full blocks."),
              interceptGetDeploys = deployHashes =>
                synchronized {
                  fetchedDeploys = fetchedDeploys union deployHashes
                  assert(
                    deployHashes.forall(h => !deployMap.contains(h)),
                    "Should not ask for existing deploys."
                  )
                }
            )
        ) {
          case (manager, backend) =>
            for {
              watches <- scheduleAll(manager)
              _       <- awaitAll(watches)
            } yield {
              fetchedDeploys should not be empty
              // Since the mock is returning partial blocks, and we verified the manager is asking for
              // partial blocks, the only way it could have downloaded and restored all the blocks
              // is if it asked for all the deploys as well.
              backend.fullBlocks should contain theSameElementsAs dag
            }
        }
      }

      "reject download if there are duplicate deploys" in {
        TestFixture(
          remote = _ =>
            MockGossipService(
              dag,
              alterDeployChunks = chunks => chunks ++ chunks
            )
        ) {
          case (manager, _) =>
            for {
              watch  <- manager.scheduleDownload(summaryOf(dag.head), source, false)
              result <- watch.attempt
            } yield {
              result match {
                case Left(ex) => ex.getMessage shouldBe "Duplicate deploy in stream."
                case Right(_) => fail("Should have failed with duplicate deploy.")
              }
            }
        }
      }

      def checkParallel(maxParallelDownloads: Int)(test: Int => Unit): Unit = {
        val parallelNow = new AtomicInteger(0)
        val parallelMax = new AtomicInteger(0)
        TestFixture(
          remote = _ =>
            MockGossipService(
              dag,
              alterBlock = task => {
                // Draw it out a little bit so it can start multiple downloads.
                Task.delay(parallelNow.incrementAndGet()) *> task.delayResult(100.millis)
              },
              alterBlockChunks = _.map { chunk =>
                parallelMax.set(math.max(parallelNow.get, parallelMax.get))
                chunk
              }
            ),
          backend = MockBackend(validate = _ => Task.delay(parallelNow.decrementAndGet()).void),
          maxParallelDownloads = maxParallelDownloads
        ) {
          case (manager, _) =>
            for {
              ws <- scheduleAll(manager)
              _  <- awaitAll(ws)
            } yield {
              test(parallelMax.get)
            }
        }
      }

      "download blocks in parallel if possible" in {
        checkParallel(maxParallelDownloads = consensusConfig.dagSize) {
          _ should be > 1
        }
      }

      "not exceed the limit on parallelism" in {
        checkParallel(maxParallelDownloads = 1) {
          _ shouldBe 1
        }
      }

      "relay blocks only specified to be relayed" in TestFixture(
        remote = _ => remote,
        relaying = relaying
      ) {
        case (manager, _) =>
          for {
            ws <- scheduleAll(manager)
            _  <- awaitAll(ws)
          } yield {
            relaying.relayed should contain theSameElementsAs relayed.map(_.blockHash)
          }
      }
    }

    "scheduled to download a block with missing dependencies" should {
      val block = sample(arbitrary[Block].suchThat(_.getHeader.parentHashes.nonEmpty))

      "raise an error" in TestFixture(MockBackend(), _ => MockGossipService(Seq(block))) {
        case (manager, _) =>
          manager.scheduleDownload(summaryOf(block), source, false).attempt.map { result =>
            result.isLeft shouldBe true
            result.left.get shouldBe a[GossipError.MissingDependencies]
          }
      }
    }

    "scheduled to download a block which already exists" should {
      val block = sample(arbitrary[Block])

      "skip the download" in TestFixture() {
        case (manager, backend) =>
          for {
            _     <- backend.store(block)
            watch <- manager.scheduleDownload(summaryOf(block), source, false)
            _     <- watch
          } yield {
            backend.blocks should have size 1
          }
      }
    }

    "scheduled to download a block which is already downloading" should {
      val block = arbitrary[Block].sample.map(withoutDependencies(_)).get
      // Delay a little so it can start two downloads if it's buggy.
      val remote = MockGossipService(Seq(block), alterBlock = _.delayResult(100.millis))

      "not download twice" in TestFixture(remote = _ => remote) {
        case (manager, backend) =>
          for {
            // Schedule twice in a row, rapidly.
            watch1 <- manager.scheduleDownload(summaryOf(block), source, false)
            watch2 <- manager.scheduleDownload(summaryOf(block), source, false)
            // Wait until both report they are complete.
            _ <- watch1
            _ <- watch2
          } yield {
            backend.blocks should have size 1
          }
      }
    }

    "released as a resource" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      "cancel outstanding downloads" in {
        @volatile var started  = false
        @volatile var finished = false
        val remote =
          MockGossipService(
            Seq(block),
            alterBlock = { get =>
              for {
                _ <- Task.delay({ started = true })
                _ <- Task.sleep(1.second)
                _ <- Task.delay({ finished = true })
                r <- get
              } yield r
            }
          )
        val backend = MockBackend()

        val test = for {
          alloc <- BlockDownloadManagerImpl[Task](
                    maxParallelDownloads = 1,
                    partialBlocksEnabled = true,
                    connectToGossip = _ => remote,
                    backend = backend,
                    relaying = MockRelaying.default,
                    retriesConf = RetriesConf.noRetries,
                    egressScheduler = implicitly[Scheduler]
                  ).allocated
          (manager, release) = alloc
          w                  <- manager.scheduleDownload(summaryOf(block), source, relay = false)
          // Allow some time for the download to start.
          _ <- Task.sleep(250.millis)
          // Cancel the download.
          _ <- release
          // Allow some time for it to finish, if it's not canceled.
          r <- w.timeout(1250.millis).attempt
        } yield {
          r.isLeft shouldBe true
          r.left.get shouldBe a[TimeoutException]
          started shouldBe true
          finished shouldBe false
          backend.blocks should not contain (block.blockHash)
        }

        test.runSyncUnsafe(5.seconds)
      }

      "reject further schedules" in {
        val test = for {
          alloc <- BlockDownloadManagerImpl[Task](
                    maxParallelDownloads = 1,
                    partialBlocksEnabled = true,
                    connectToGossip = _ => MockGossipService(),
                    backend = MockBackend(),
                    relaying = MockRelaying.default,
                    retriesConf = RetriesConf.noRetries,
                    egressScheduler = implicitly[Scheduler]
                  ).allocated
          (manager, release) = alloc
          _                  <- release
          res                <- manager.scheduleDownload(summaryOf(block), source, relay = false).attempt
        } yield {
          res.isLeft shouldBe true
          res.left.get shouldBe a[java.lang.IllegalStateException]
        }

        test.runSyncUnsafe(1.seconds)
      }
    }

    "fails to download a block from the first source" should {
      val block = sample(arbitrary[Block].map(withoutDependencies(_)))
      val nodeA = sample(arbitrary[Node])
      val nodeB = sample(arbitrary[Node])

      val remote = (node: Node) =>
        node match {
          case `nodeA` =>
            MockGossipService(
              Seq(block),
              alterBlock = _.delayResult(100.millis) *> Task.raiseError(
                new Exception("Node A is dying!")
              )
            )
          case _ => MockGossipService(Seq(block))
        }

      "try to download the block from a different source" in TestFixture(remote = remote) {
        case (manager, backend) =>
          if (sys.env.contains("DRONE_BRANCH")) {
            cancel("NODE-1038")
          }
          for {
            w1 <- manager.scheduleDownload(summaryOf(block), nodeA, false)
            w2 <- manager.scheduleDownload(summaryOf(block), nodeB, false)
            // Both should be successful eventually.
            _ <- w1
            _ <- w2
          } yield {
            if (sys.env.contains("DRONE_BRANCH")) {
              cancel("NODE-1038")
            }
            log.warns should have size 1
            log.warns.head should include("Node A is dying!")
            backend.blocks should contain(block.blockHash)
          }
      }
    }

    "downloaded a valid block" should {
      val block   = arbitrary[Block].sample.map(withoutDependencies).get
      val remote  = MockGossipService(Seq(block))
      def backend = MockBackend()

      def check(test: MockBackend => Unit): TestArgs => Task[Unit] = {
        case (manager, backend) =>
          for {
            w <- manager.scheduleDownload(summaryOf(block), source, relay = true)
            _ <- w
          } yield {
            test(backend)
          }
      }

      "validate the block" in TestFixture(backend, _ => remote) {
        check(_.validations should contain(block.blockHash))
      }

      "store the block" in TestFixture(backend, _ => remote) {
        check(_.blocks should contain(block.blockHash))
      }
    }

    "cannot validate a block" should {
      val dag    = sample(genBlockDagFromGenesis)
      val remote = MockGossipService(dag)
      def backend =
        MockBackend(_ => Task.raiseError(new java.lang.IllegalArgumentException("Nope.")))

      "not store the block" in TestFixture(backend, _ => remote) {
        case (manager, backend) =>
          val block = dag.head
          for {
            w <- manager.scheduleDownload(summaryOf(block), source, false)
            _ <- w.attempt
          } yield {
            backend.validations should contain(block.blockHash)
            backend.blocks should not contain (block.blockHash)
            Inspectors.forExactly(1, log.causes) {
              _.getMessage shouldBe "Nope."
            }
          }
      }

      "not download the dependant blocks" in TestFixture(backend, _ => remote) {
        case (manager, backend) =>
          for {
            w <- manager.scheduleDownload(summaryOf(dag(0)), source, false)
            _ <- manager.scheduleDownload(summaryOf(dag(1)), source, false)
            _ <- w.attempt
            _ <- Task.sleep(250.millis) // 2nd should never be attempted.
          } yield {
            backend.validations should contain(dag(0).blockHash)
            backend.validations should not contain (dag(1).blockHash)
          }
      }
    }

    "cannot connect to a node" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      "try again if the same block from the same source is scheduled again after the previous attempt is finished" in TestFixture(
        remote = _ => Task.raiseError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
      ) {
        case (manager, _) =>
          for {
            w1 <- manager.scheduleDownload(summaryOf(block), source, false)
            r1 <- w1.attempt
            w2 <- manager.scheduleDownload(summaryOf(block), source, false)
            r2 <- w2.attempt
          } yield {
            Inspectors.forAll(Seq(r1, r2)) { r =>
              r.isLeft shouldBe true
              r.left.get shouldBe an[io.grpc.StatusRuntimeException]
            }
            log.causes should have size 2
            log.causes.head shouldBe r1.left.get
            log.causes.last shouldBe r2.left.get
          }
      }

      "try again with another source and start applying exponential backoff if all peers tried" in {
        val nodeA = sample(arbitrary[Node])
        val nodeB = sample(arbitrary[Node])

        final case class Scheduling(attempt: Int, delay: FiniteDuration, node: Node)
        final case class Retrying(attempt: Int, node: Node)

        def attempt(logLine: String): Int =
          ".*attempt=(\\d+).*".r.unapplySeq(logLine).get.head.toInt

        def delay(logLine: String): FiniteDuration =
          Duration(".*delay=(.+)".r.unapplySeq(logLine).get.head).asInstanceOf[FiniteDuration]

        def node(logLine: String): Node = {
          val id = ".*casperlabs:\\/\\/([a-f0-9]{64}).*".r.unapplySeq(logLine).get.head
          List(nodeA, nodeB).find(_.show.contains(id)).get
        }

        def schedules: Vector[Scheduling] = log.debugs.collect {
          case s if s.contains("Scheduling") => Scheduling(attempt(s), delay(s), node(s))
        }
        def retryings: Vector[Retrying] = log.warns.collect {
          case s if s.contains("Retrying") => Retrying(attempt(s), node(s))
        }

        TestFixture(
          remote = _ => Task.raiseError(io.grpc.Status.UNAVAILABLE.asRuntimeException()),
          retriesConf =
            RetriesConf(maxRetries = 1, initialBackoffPeriod = 1.second, backoffFactor = 2.0),
          timeout = 10.seconds
        ) {
          case (manager, _) =>
            // S(n,m): where n - number of schedules, m - A if nodeA, B if nodeB, example SA0, SB3
            // R(n,m): where n - number of retryings, m - A if nodeA, B if nodeB, example RA2, RB1
            //
            // Expected pattern is:
            //
            //  - initially SA0, SB0, RA0, RB0
            //
            //  - nodeA scheduled and immediately failed, SA1, RA1
            //
            //  - nodeA immediately rescheduled with delay of 1s, SA2, (during delay [0s:1s]: Stage A) and failed (RA2 at 1s),
            //    nodeA is chosen because adding of nodeB isn't fast enough, nodeB is delayed a bit to make test reproducible,
            //    otherwise it will be non-deterministic and logic will be difficult to test
            //
            //  - nodeB scheduled and immediately failed, SB1 RB1
            //
            //  - nodeB rescheduled with delay of 1s, SB2, (during delay [1s:2s]: Stage B) and failed (RB2 at 2s),
            //    nodeB is chosen because it's queried less than nodeA (has the minimal counter among all sources)
            //
            //  - either nodeA OR nodeB is chosen and then checking that the counter is > 1
            //    and whole block downloading fails, this is Stage C

            for {
              w1 <- manager
                     .scheduleDownload(summaryOf(block), nodeA, relay = false)
              _ <- Task.sleep(100.millis)
              w2 <- manager
                     .scheduleDownload(summaryOf(block), nodeB, relay = false)
              _ <- Task.sleep(500.millis)
              _ <- Task {
                    // Stage A, 0.5s
                    schedules.size shouldBe 2

                    schedules(0).attempt shouldBe 0
                    schedules(1).attempt shouldBe 1

                    schedules(0).delay shouldBe Duration.Zero
                    schedules(1).delay shouldBe 1.second

                    schedules(0).node shouldBe nodeA
                    schedules(1).node shouldBe nodeA

                    retryings.size shouldBe 1
                    retryings(0).attempt shouldBe 0
                    retryings(0).node shouldBe nodeA
                  }
              _ <- Task.sleep(1.second)
              _ <- Task {
                    // Stage B, 1.5s
                    schedules.size shouldBe 4

                    schedules(2).attempt shouldBe 0
                    schedules(3).attempt shouldBe 1

                    schedules(2).delay shouldBe Duration.Zero
                    schedules(3).delay shouldBe 1.second

                    schedules(2).node shouldBe nodeB
                    schedules(3).node shouldBe nodeB

                    retryings.size shouldBe 3
                    retryings(1).attempt shouldBe 1
                    retryings(1).node shouldBe nodeA

                    retryings(2).attempt shouldBe 0
                    retryings(2).node shouldBe nodeB
                  }
              _ <- Task.sleep(1500.millis)
              _ <- Task {
                    // Stage C, 3s
                    retryings.size shouldBe 4
                    log.errors.size shouldBe 1
                  }
              r1 <- w1.attempt
              r2 <- w2.attempt
            } yield {
              r1.isLeft shouldBe true
              r2.isLeft shouldBe true

              r1.left.get shouldBe an[io.grpc.StatusRuntimeException]
              r2.left.get shouldBe an[io.grpc.StatusRuntimeException]
            }
        }
      }
    }

    "receiving chunks" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      def check(args: TestArgs, msg: String): Task[Unit] =
        for {
          w <- args._1.scheduleDownload(summaryOf(block), source, false)
          _ <- w.attempt
        } yield {
          Inspectors.forExactly(1, log.causes) { ex =>
            ex shouldBe a[GossipError.InvalidChunks]
            ex.getMessage should include(msg)
          }
        }

      def withRechunking(f: Iterant[Task, Chunk] => Iterant[Task, Chunk]) = { _: Node =>
        MockGossipService(Seq(block), alterBlockChunks = f)
      }

      def rewriteHeader(
          f: Chunk.Header => Chunk.Header
      ): Iterant[Task, Chunk] => Iterant[Task, Chunk] = { it =>
        it.take(1).map { chunk =>
          assert(chunk.content.isHeader)
          chunk.withHeader(f(chunk.getHeader))
        } ++ it.tail
      }

      "check that they start with the header" in TestFixture(
        remote = withRechunking(_.tail)
      )(check(_, "did not start with a header"))

      "check that the total size doesn't exceed promises" in TestFixture(
        remote = withRechunking(rewriteHeader(_.copy(contentLength = 0)))
      )(check(_, "exceeding the promised content length"))

      "check that the expected compression algorithm is used" in TestFixture(
        remote = withRechunking(rewriteHeader(_.copy(compressionAlgorithm = "gzip")))
      )(check(_, "unexpected algorithm"))

      "handle an empty stream" in TestFixture(
        remote = withRechunking(_.take(0))
      )(check(_, "not receive a header"))

      "handle a stream that ends prematurely" in TestFixture(
        remote = withRechunking(_.take(1))
      )(check(_, "not decompress"))
    }

    "cannot query the backend" should {
      val block1 = sample(arbitrary[Block])
      val block2 = sample(arbitrary[Block].map(withoutDependencies))
      val remote = MockGossipService(Seq(block1, block2))
      val backend = new MockBackend() {
        override def contains(blockHash: ByteString) =
          if (blockHash == block1.blockHash || dependenciesOf(block1).contains(blockHash))
            Task.raiseError(new RuntimeException("Oh no!"))
          else
            Task.pure(false)
      }

      "raise a scheduling error, but keep processing schedules" in TestFixture(backend, _ => remote) {
        case (manager, _) =>
          for {
            attempt1 <- manager.scheduleDownload(summaryOf(block1), source, false).attempt
            attempt2 <- manager.scheduleDownload(summaryOf(block2), source, false).attempt
          } yield {
            attempt1 match {
              case Left(ex: RuntimeException) =>
                ex.getMessage shouldBe "Oh no!"
              case other =>
                fail(s"Expected scheduling to fail; got $other")
            }
            attempt2.isRight shouldBe true
          }
      }
    }
  }
}

object BlockDownloadManagerSpec {
  implicit val metrics = new Metrics.MetricsNOP[Task]

  def summaryOf(block: Block): BlockSummary =
    BlockSummary()
      .withBlockHash(block.blockHash)
      .withHeader(block.getHeader)
      .withSignature(block.getSignature)

  def dependenciesOf(block: Block): Seq[ByteString] =
    block.getHeader.parentHashes ++ block.getHeader.justifications.map(_.latestBlockHash)

  def withoutDependencies(block: Block): Block =
    block.withHeader(block.getHeader.copy(parentHashes = Seq.empty, justifications = Seq.empty))

  def toBlockMap(blocks: Seq[Block]) =
    blocks.groupBy(_.blockHash).mapValues(_.head)

  type TestArgs = (BlockDownloadManager[Task], MockBackend)

  object TestFixture {
    def apply(
        backend: MockBackend = MockBackend.default,
        remote: Node => Task[GossipService[Task]] = _ => MockGossipService.default,
        maxParallelDownloads: Int = 1,
        relaying: MockRelaying = MockRelaying.default,
        retriesConf: RetriesConf = RetriesConf.noRetries,
        timeout: FiniteDuration = 5.seconds
    )(
        test: TestArgs => Task[Unit]
    )(implicit scheduler: Scheduler, log: Log[Task]): Unit = {

      val managerR = BlockDownloadManagerImpl[Task](
        maxParallelDownloads = maxParallelDownloads,
        partialBlocksEnabled = true,
        connectToGossip = remote(_),
        backend = backend,
        relaying = relaying,
        retriesConf = retriesConf,
        egressScheduler = scheduler
      )

      val runTest = managerR.use { manager =>
        test((manager, backend))
      }

      runTest.runSyncUnsafe(timeout)
    }
  }

  class MockBackend(validationFunction: Block => Task[Unit] = _ => Task.unit)
      extends BlockDownloadManagerImpl.Backend[Task] {
    // Record what we have been called with.
    @volatile var validations = Vector.empty[ByteString]
    @volatile var scheduled   = Vector.empty[ByteString]
    @volatile var downloaded  = Vector.empty[ByteString]
    @volatile var blocks      = Vector.empty[ByteString]
    @volatile var fullBlocks  = Vector.empty[Block]

    def contains(blockHash: ByteString): Task[Boolean] =
      Task.now(blocks.contains(blockHash))

    def validate(block: Block): Task[Unit] =
      Task.delay {
        synchronized { validations = validations :+ block.blockHash }
      } *> validationFunction(block)

    def store(block: Block): Task[Unit] = Task.delay {
      synchronized {
        blocks = blocks :+ block.blockHash
        fullBlocks = fullBlocks :+ block
      }
    }

    def onScheduled(summary: BlockSummary, source: Node): Task[Unit] = Task.unit

    def onScheduled(summary: BlockSummary): Task[Unit] = Task.delay {
      synchronized { scheduled = scheduled :+ summary.blockHash }
    }

    def onDownloaded(blockHash: ByteString): Task[Unit] = Task.delay {
      synchronized { downloaded = downloaded :+ blockHash }
    }

    def onFailed(blockHash: ByteString): Task[Unit] = Task.unit

    def readDeploys(deployHashes: Seq[ByteString]): Task[List[Deploy]] = Task.pure(Nil)
  }
  object MockBackend {
    def default                                               = apply()
    def apply(validate: Block => Task[Unit] = _ => Task.unit) = new MockBackend(validate)
  }

  class MockRelaying extends BlockRelaying[Task] {
    @volatile var relayed = Vector.empty[ByteString]

    override def relay(hashes: List[ByteString]): Task[Task[Unit]] = Task.delay {
      synchronized { relayed = relayed ++ hashes }
      Task.unit
    }
  }
  object MockRelaying {
    def default: MockRelaying = apply()
    def apply(): MockRelaying = new MockRelaying()
  }

  /** Test implementation of the remote GossipService to download the blocks from. */
  object MockGossipService {
    private val emptySynchronizer          = new NoOpsSynchronizer[Task]          {}
    private val emptyDeployDownloadManager = new NoOpsDeployDownloadManager[Task] {}
    private val emptyBlockDownloadManager  = new NoOpsBlockDownloadManager[Task]  {}
    private val emptyGenesisApprover       = new NoOpsGenesisApprover[Task]       {}

    // Used only as a default argument for when we aren't touching the remote service in a test.
    val default = {
      implicit val log = Log.NOPLog[Task]
      GossipServiceServer[Task](
        backend = new NoOpsGossipServiceServerBackend[Task] {
          override def getBlock(blockHash: ByteString, deploysBodiesExcluded: Boolean) =
            Task.now(None)

          override def getDeploys(deployHashes: Set[ByteString]) = Iterant.empty[Task, Deploy]
        },
        synchronizer = emptySynchronizer,
        connector = _ => ???,
        deployDownloadManager = emptyDeployDownloadManager,
        blockDownloadManager = emptyBlockDownloadManager,
        genesisApprover = emptyGenesisApprover,
        maxChunkSize = 100 * 1024,
        maxParallelBlockDownloads = 100,
        deployGossipEnabled = false
      )
    }

    def apply(
        blocks: Seq[Block] = Seq.empty,
        // Pass in a `alterBlockChunks` method to alter the stream of block chunks returned by `getBlockChunked`.
        alterBlockChunks: Iterant[Task, Chunk] => Iterant[Task, Chunk] = identity,
        alterDeployChunks: Iterant[Task, Chunk] => Iterant[Task, Chunk] = identity,
        // Pass in a `alterBlock` method to alter the behaviour of the `getBlock`, for example to add delays.
        alterBlock: Task[Option[Block]] => Task[Option[Block]] = identity,
        interceptGetBlock: (ByteString, Boolean) => Unit = (_, _) => (),
        interceptGetDeploys: (Set[ByteString]) => Unit = _ => ()
    )(implicit log: Log[Task]) =
      for {
        blockMap  <- Task.now(toBlockMap(blocks))
        semaphore <- Semaphore[Task](100)
      } yield {
        // Using `new` because I want to override `getBlockChunked`.
        new GossipServiceServer[Task](
          backend = new NoOpsGossipServiceServerBackend[Task] {
            override def getBlock(blockHash: ByteString, deploysBodiesExcluded: Boolean) =
              Task.delay(interceptGetBlock(blockHash, deploysBodiesExcluded)) *>
                alterBlock(Task.delay(blockMap.get(blockHash).map { block =>
                  if (deploysBodiesExcluded) {
                    block.clearDeployBodies
                  } else {
                    block
                  }
                }))

            override def getDeploys(deployHashes: Set[ByteString]) =
              Iterant.pure(interceptGetDeploys(deployHashes)).flatMap { _ =>
                Iterant.fromIterable(
                  blocks
                    .flatMap(_.getBody.deploys.map(_.getDeploy))
                    .toSet
                    .filter(d => deployHashes(d.deployHash))
                )
              }
          },
          synchronizer = emptySynchronizer,
          connector = _ => ???,
          deployDownloadManager = emptyDeployDownloadManager,
          blockDownloadManager = emptyBlockDownloadManager,
          genesisApprover = emptyGenesisApprover,
          maxChunkSize = 100 * 1024,
          deployGossipEnabled = false,
          blockDownloadSemaphore = semaphore
        ) {
          override def getBlockChunked(request: GetBlockChunkedRequest) =
            alterBlockChunks(super.getBlockChunked(request))

          override def streamDeploysChunked(request: StreamDeploysChunkedRequest) =
            alterDeployChunks(super.streamDeploysChunked(request))
        }
      }
  }
}
