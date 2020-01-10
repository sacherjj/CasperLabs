package io.casperlabs.comm.gossiping

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import cats.Id
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Approval, Block, BlockSummary, GenesisCandidate}
import io.casperlabs.catscontrib.effect.implicits.syncId
import io.casperlabs.comm.ServiceError.{NotFound, ResourceExhausted, Unauthenticated, Unavailable}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.synchronization.Synchronizer.SyncError
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.comm.gossiping.synchronization.Synchronizer
import io.casperlabs.comm.grpc.{AuthInterceptor, ErrorInterceptor, GrpcServer, SslContexts}
import io.casperlabs.comm.{ServiceError, TestRuntime}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.util.{CertificateHelper, CertificatePrinter}
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.shared.{Compression, Log}
import io.grpc.netty.{NegotiationType, NettyChannelBuilder}
import io.netty.handler.ssl.ClientAuth
import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.{ExecutionModel, Scheduler}
import monix.reactive.Observable
import monix.tail.Iterant
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll, PropertyCheckConfiguration}

import scala.concurrent.duration._

class GrpcGossipServiceSpec
    extends refspec.RefSpecLike
    with Eventually
    with Matchers
    with BeforeAndAfterAll
    with SequentialNestedSuiteExecution
    with ArbitraryConsensusAndComm { self =>

  import GrpcGossipServiceSpec.TestEnvironment.chainId
  import GrpcGossipServiceSpec._
  import Scheduler.Implicits.global

  // Test data that we can set in each test.
  val testDataRef = new AtomicReference(TestData.empty)
  // Set up the server and client once, to be shared, to make tests faster.
  val stubCert                                   = TestCert.generate
  var stub: GossipingGrpcMonix.GossipServiceStub = _
  var shutdown: Task[Unit]                       = _

  implicit val noShrinkInt: Shrink[Int] = Shrink.shrinkAny

  override def beforeAll(): Unit =
    TestEnvironment(testDataRef, clientCert = Some(stubCert)).allocated.foreach {
      case (stub, shutdown) =>
        this.stub = stub
        this.shutdown = shutdown
    }

  override def afterAll() =
    shutdown.runSyncUnsafe(10.seconds)

  def runTestUnsafe(testData: TestData, timeout: FiniteDuration = 5.seconds)(
      test: Task[Unit]
  ): Unit = {
    testDataRef.set(testData)
    test.runSyncUnsafe(timeout)
  }

  override def nestedSuites = Vector(
    GetBlockChunkedSpec,
    StreamBlockSummariesSpec,
    StreamAncestorBlockSummariesSpec,
    StreamLatestMessagesSpec,
    NewBlocksSpec,
    GenesisApprovalSpec,
    StreamDagSliceBlockSummariesSpec
  )

  trait AuthSpec extends WordSpecLike {
    implicit val hashGen: Arbitrary[ByteString] = Arbitrary(genHash)
    implicit val consensusConfig =
      ConsensusConfig(dagSize = 10, maxSessionCodeBytes = 50, maxPaymentCodeBytes = 10)
    implicit val patienceConfig = PatienceConfig(3.second, 100.millis)
    val validSenderGen          = arbNode.arbitrary.map(_.withId(stubCert.keyHash))

    def rpcName: String

    def query
        : (Option[Node], List[ByteString]) => GossipingGrpcMonix.GossipServiceStub => Task[Unit]

    def ignoreSender: Boolean

    def expectError(
        client: GossipingGrpcMonix.GossipServiceStub,
        request: GossipingGrpcMonix.GossipServiceStub => Task[Unit]
    )(pf: PartialFunction[Throwable, Unit]): Task[Unit] =
      request(client).attempt.map { res =>
        res.isLeft shouldBe true
        pf.lift(res.left.get) getOrElse {
          fail(s"Unexpected error: ${res.left.get}")
        }
      }

    rpcName when {
      "called with a problematic sender" when {
        implicit val config = PropertyCheckConfiguration(minSuccessful = 1, minSize = 1)

        if (!ignoreSender) {
          "called with a sender whose ID doesn't match its SSL public key" should {
            "return UNAUTHENTICATED" in {
              forAll { (block: Block, sender: Node) =>
                runTestUnsafe(TestData.fromBlock(block)) {
                  expectError(stub, query(sender.some, List(block.blockHash))) {
                    case Unauthenticated(msg) =>
                      msg shouldBe "Sender doesn't match public key."
                  }
                }
              }
            }
          }

          "called with a sender whose chain ID doesn't match expected" should {
            "return UNAUTHENTICATED" in {
              forAll { (block: Block, sender: Node, chainId: ByteString) =>
                runTestUnsafe(TestData.fromBlock(block)) {
                  expectError(stub, query(sender.withChainId(chainId).some, List(block.blockHash))) {
                    case Unauthenticated(msg) =>
                      msg shouldBe s"Sender doesn't match chain id, expected: ${hex(
                        TestEnvironment.chainId
                      )}, received: ${hex(chainId)}"
                  }
                }
              }
            }
          }
        }

        "called without an SSL certificate" when {

          def expectErrorWithAnonymous(
              clientAuth: ClientAuth
          )(pf: PartialFunction[Throwable, Unit]) =
            forAll { (block: Block, sender: Node) =>
              runTestUnsafe(TestData.fromBlock(block)) {
                TestEnvironment(testDataRef, clientCert = None, clientAuth = clientAuth).use {
                  anonymousStub =>
                    expectError(anonymousStub, query(sender.some, List(block.blockHash)))(pf)
                }
              }
            }

          "client auth is required" should {
            "return UNAVAILABLE" in {
              expectErrorWithAnonymous(ClientAuth.REQUIRE) {
                case ex: io.grpc.StatusRuntimeException =>
                  // Becuase the server requires client auth this will be rejected straight away.
                  ex.getStatus.getCode shouldBe io.grpc.Status.Code.UNAVAILABLE
              }
            }
          }

          "client auth is not required (due to misconfiguration)" should {
            "return UNAUTHENTICATED" in {
              expectErrorWithAnonymous(ClientAuth.NONE) {
                case Unauthenticated(msg) =>
                  msg shouldBe "Cannot verify sender identity."
              }
            }
          }
        }
      }
    }
  }

  trait RateSpec extends WordSpecLike { self: AuthSpec =>
    rpcName when {
      "called with a valid sender" when {
        implicit val config = PropertyCheckConfiguration(minSuccessful = 1, minSize = 1)

        def test(block: Block, queueSize: Int)(
            test: (GossipingGrpcMonix.GossipServiceStub) => Task[Unit]
        ): Unit =
          runTestUnsafe(TestData.fromBlock(block), timeout = 10.seconds) {
            val resources = for {
              rateLimiter <- RateLimiter
                              .create[Task, ByteString](
                                elementsPerPeriod = 1,
                                period = 1.second,
                                maxQueueSize = queueSize
                              )
              stub <- TestEnvironment(
                       testDataRef,
                       clientCert = Some(stubCert),
                       rateLimiter = rateLimiter
                     )
            } yield stub

            resources.use(test)
          }

        "rate is exceeded" when {
          "queue is full" should {
            "return RESOURCE_EXHAUSTED" in forAll(arbitrary[Block], validSenderGen) {
              (block, sender) =>
                val requestsNum = 10
                val minFailed   = 5
                test(block, queueSize = 1) { stub =>
                  for {
                    errors <- Task
                               .gatherUnordered(
                                 List.fill(requestsNum)(
                                   query(sender.some, List(block.blockHash))(stub)
                                     .redeem[Option[Throwable]](
                                       _.some,
                                       _ => none[Throwable]
                                     )
                                 )
                               )
                               .map(_.flatten)
                  } yield {
                    // At least first request occupies the single available place in queue
                    // and will be successful
                    // Not comparing with precise number, because it may vary in CI and fail
                    assert(errors.size >= minFailed && errors.size < requestsNum)
                    Inspectors.forAll(errors) { e =>
                      ResourceExhausted.unapply(e) shouldBe Some("Rate exceeded")
                    }
                  }
                }
            }
          }
          "queue isn't full" should {
            "throttle" in forAll(arbitrary[Block], validSenderGen) { (block, sender) =>
              val requestsNum   = 5
              val queueSize     = 10
              val minSuccessful = 2

              implicit val patienceConfig = PatienceConfig(10.seconds, 500.millis)

              test(block, queueSize) { stub =>
                val success = Atomic(0)
                val errors  = Atomic(0)
                val runParallelRequests = Task.gatherUnordered(
                  List.fill(requestsNum)(
                    query(sender.some, List(block.blockHash))(stub)
                      .redeemWith[Unit](
                        _ => Task(errors.increment()),
                        _ => Task(success.increment())
                      )
                  )
                )

                for {
                  _ <- runParallelRequests.startAndForget
                } yield {
                  eventually {
                    assert(errors.get() == 0)
                    // Not comparing with precise number, because it may vary in CI and fail
                    assert(success.get() >= minSuccessful && success.get() < requestsNum)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  object GetBlockChunkedSpec extends WordSpecLike with AuthSpec with RateSpec {
    implicit val propCheckConfig         = PropertyCheckConfiguration(minSuccessful = 1)
    implicit override val patienceConfig = PatienceConfig(15.seconds, 500.millis)
    implicit override val consensusConfig = ConsensusConfig(
      maxSessionCodeBytes = 2500 * 1024,
      minSessionCodeBytes = 1500 * 1024,
      maxPaymentCodeBytes = 900 * 1024,
      minPaymentCodeBytes = 800 * 1024
    )

    override def rpcName: String = "getBlocksChunked"

    override def query
        : (Option[Node], List[ByteString]) => GossipingGrpcMonix.GossipServiceStub => Task[Unit] =
      (_, blockHashes) =>
        client =>
          client
            .getBlockChunked(
              GetBlockChunkedRequest(blockHash = blockHashes.head)
            )
            .toListL
            .void

    override def ignoreSender: Boolean = true

    "getBlocksChunked" when {
      "called with a valid sender" when {
        "no compression is supported" should {
          "return a stream of uncompressed chunks" in {
            forAll { block: Block =>
              runTestUnsafe(TestData.fromBlock(block), timeout = 15.seconds) {
                val req = GetBlockChunkedRequest(blockHash = block.blockHash)
                stub.getBlockChunked(req).toListL.map { chunks =>
                  chunks.head.content.isHeader shouldBe true
                  val header = chunks.head.getHeader
                  header.compressionAlgorithm shouldBe ""
                  chunks.size should be > 1

                  Inspectors.forAll(chunks.tail) { chunk =>
                    chunk.content.isData shouldBe true
                    chunk.getData.size should be <= DefaultMaxChunkSize
                  }

                  val content  = chunks.tail.flatMap(_.getData.toByteArray).toArray
                  val original = block.toByteArray
                  header.contentLength shouldBe content.length
                  header.originalContentLength shouldBe original.length
                  md5(content) shouldBe md5(original)
                }
              }
            }
          }
        }

        "compression is supported" should {
          "return a stream of compressed chunks" in {
            forAll { block: Block =>
              runTestUnsafe(TestData.fromBlock(block)) {
                val req = GetBlockChunkedRequest(
                  blockHash = block.blockHash,
                  acceptedCompressionAlgorithms = Seq("lz4")
                )

                stub.getBlockChunked(req).toListL.map { chunks =>
                  chunks.head.content.isHeader shouldBe true
                  val header = chunks.head.getHeader
                  header.compressionAlgorithm shouldBe "lz4"

                  val content  = chunks.tail.flatMap(_.getData.toByteArray).toArray
                  val original = block.toByteArray
                  header.contentLength shouldBe content.length
                  header.originalContentLength shouldBe original.length

                  val decompressed = Compression
                    .decompress(content, header.originalContentLength)
                    .get

                  md5(decompressed) shouldBe md5(original)
                }
              }
            }
          }
        }

        "chunk size is specified" when {
          def testChunkSize(
              block: Block,
              requestedChunkSize: Int,
              expectedChunkSize: Int
          ): Unit =
            runTestUnsafe(TestData.fromBlock(block)) {
              val req =
                GetBlockChunkedRequest(
                  blockHash = block.blockHash,
                  chunkSize = requestedChunkSize
                )
              stub.getBlockChunked(req).toListL.map { chunks =>
                Inspectors.forAll(chunks.tail.init) { chunk =>
                  chunk.getData.size shouldBe expectedChunkSize
                }
                chunks.last.getData.size should be <= expectedChunkSize
              }
            }

          "it is less then the maximum" should {
            "use the requested chunk size" in {
              forAll { block: Block =>
                val smallChunkSize = DefaultMaxChunkSize / 2
                testChunkSize(block, smallChunkSize, smallChunkSize)
              }
            }
          }

          "bigger than the maximum" should {
            "use the default chunk size" in {
              forAll { block: Block =>
                val bigChunkSize = DefaultMaxChunkSize * 2
                testChunkSize(block, bigChunkSize, DefaultMaxChunkSize)
              }
            }
          }
        }

        "block cannot be found" should {
          "return NOT_FOUND" in {
            forAll { hash: ByteString =>
              runTestUnsafe(TestData.empty) {
                val req = GetBlockChunkedRequest(blockHash = hash)
                stub.getBlockChunked(req).toListL.attempt.map { res =>
                  res.isLeft shouldBe true
                  res.left.get match {
                    case NotFound(msg) =>
                      msg shouldBe s"Block ${Base16.encode(hash.toByteArray)} could not be found."
                    case ex =>
                      fail(s"Unexpected error: $ex")
                  }
                }
              }
            }
          }
        }

        "iteration is abandoned" should {
          "cancel the source" in {
            forAll { block: Block =>
              runTestUnsafe(TestData.fromBlock(block), timeout = 1.minute) {
                // Capture the event when the Observable created from the Iterant is canceled.
                var stopCount     = 0
                var nextCount     = 0
                var completeCount = 0

                val oi = new ObservableIterant[Task] {
                  // This should count on the server side.
                  def toObservable[A](it: Iterant[Task, A]) =
                    Observable
                      .fromReactivePublisher(it.toReactivePublisher)
                      .doOnNext(_ => Task.delay(nextCount += 1))
                      .doOnEarlyStop(Task.delay(stopCount += 1))
                      .doOnComplete(Task.delay(completeCount += 1))
                  // This should limit how much data the client is asking.
                  // Except the code generated by GrpcMonix is using an independent buffer size.
                  def toIterant[A](obs: Observable[A]) =
                    Iterant.fromReactivePublisher[Task, A](
                      obs.toReactivePublisher,
                      requestCount = 1,
                      eagerBuffer = false
                    )
                }

                // Restrict the client to request 1 item at a time.
                val scheduler = Scheduler(ExecutionModel.BatchedExecution(1))

                TestEnvironment(testDataRef, clientCert = stubCert.some)(oi, scheduler).use {
                  stub =>
                    // Turn the stub (using Observables) back to the internal interface (using Iterant).
                    val svc = GrpcGossipService.toGossipService[Task](stub)
                    val req = GetBlockChunkedRequest(blockHash = block.blockHash)
                    for {
                      // Consume just the head, cancel the rest. This could be used to keep track of total content size.
                      maybeHeader <- svc
                                      .getBlockChunked(req)
                                      .foldWhileLeftEvalL(Task.now(none[Chunk.Header])) {
                                        case (None, chunk) if chunk.content.isHeader =>
                                          Task.now(Right(Some(chunk.getHeader)))
                                        case _ =>
                                          Task.now(Left(None))
                                      }
                      firstCount <- Task.delay(nextCount)
                      all        <- svc.getBlockChunked(req).toListL
                    } yield {
                      maybeHeader should not be empty

                      // The abandoned stream should be completed.
                      eventually {
                        completeCount shouldBe 2
                      }

                      // We should stop early, and with the batch restriction just after a few items pulled.
                      firstCount should be < all.size

                      // This worked when we weren't going over gRPC, just using the abstractions.
                      // I'll leave it as a reminder, but the assertion on completion and message count should indicate early stop.
                      // To be fair doOnEarlyStop didn't seem to trigger with simple Observable(1,2,3) either.
                      //stopCount shouldBe 1
                    }
                }
              }
            }
          }
        }

        "many downloads are attempted at once" should {
          "only allow them up to the limit" in {
            val maxParallelBlockDownloads = 2
            forAll { block: Block =>
              runTestUnsafe(TestData.fromBlock(block), timeout = 15.seconds) {
                TestEnvironment(
                  testDataRef,
                  maxParallelBlockDownloads = maxParallelBlockDownloads,
                  clientCert = stubCert.some
                ).use { stub =>
                  val parallelNow = new AtomicInteger(0)
                  val parallelMax = new AtomicInteger(0)

                  val req =
                    GetBlockChunkedRequest(blockHash = block.blockHash)
                  val fetchers = List.fill(maxParallelBlockDownloads * 5) {
                    stub
                      .getBlockChunked(req)
                      .doOnStart(_ => Task.delay { parallelNow.incrementAndGet() })
                      .doOnNext(
                        _ =>
                          Task.delay {
                            parallelMax.set(math.max(parallelMax.get, parallelNow.get))
                          }
                      )
                      .doOnComplete(Task.delay { parallelNow.decrementAndGet() })
                      .toListL
                  }

                  Task.gatherUnordered(fetchers) map { res =>
                    res.size shouldBe fetchers.size
                    // We may see some overlap between completion and the start of the next
                    // due to the fact that gRPC will do client side buffering too.
                    parallelMax.get should be <= (maxParallelBlockDownloads * 2)
                    parallelMax.get should be >= maxParallelBlockDownloads
                  }
                }
              }
            }
          }
        }

        "a download is not consumed" should {
          "cancel the idle stream" in {
            // Tried to test this with short timeouts and delays but it looks like underlying gRPC
            // reactive subscriber machinery will eagerly pull all the data from the server regardless
            // of the backpressure applied in the subsequent processing. Nevertheless the timeout is
            // applied so if someone tries to go deeper we should be covered.
            // TODO: This test randomly fails in Drone CI.
            //       I've tried to wrap it into 'eventually' but it didn't help.
            //       Sometimes it's passing, but sometimes not.
            //       Locally, it passes in 100% cases.
            //       Decided to disable this test in CI for the time being.
            if (sys.env.contains("DRONE_BRANCH")) {
              cancel("On Drone it sometimes returns `false` for some inexplicable reason.")
            }

            val block = sample(arbitrary[Block])
            runTestUnsafe(TestData.fromBlock(block), timeout = 15.seconds) {
              TestEnvironment(
                testDataRef,
                maxParallelBlockDownloads = 1,
                blockChunkConsumerTimeout = Duration.Zero,
                clientCert = stubCert.some
              ).use { stub =>
                val req = GetBlockChunkedRequest(blockHash = block.blockHash)

                for {
                  r <- stub.getBlockChunked(req).toListL.attempt
                  _ = {
                    r.isLeft shouldBe true
                    r.left.get match {
                      case ex: io.grpc.StatusRuntimeException =>
                        ex.getStatus.getCode shouldBe io.grpc.Status.Code.DEADLINE_EXCEEDED
                      case other =>
                        fail(s"Unexpected error: $other")
                    }
                  }
                  // The semaphore should be free for the next query. Otherwise the test will time out.
                  _ <- stub.getBlockChunked(req).headL
                } yield ()
              }
            }
          }
        }

        "an error is thrown" should {
          "release the download semaphore" in {
            forAll { hash: ByteString =>
              @volatile var cnt = 0

              val faultyBackend = (_: AtomicReference[TestData]) => {
                new GossipServiceServer.Backend[Task] {
                  def getBlock(blockHash: ByteString) = {
                    cnt = cnt + 1
                    cnt match {
                      case 1 =>
                        Task.raiseError[Option[Block]](new RuntimeException("Delayed Boom!"))
                      case 2 =>
                        sys.error("Immediate Boom!")
                      case _ =>
                        Task.now(None)
                    }
                  }
                  def hasBlock(blockHash: ByteString)                = ???
                  def getBlockSummary(blockHash: ByteString)         = ???
                  def latestMessages: Task[Set[Block.Justification]] = ???
                  def dagTopoSort(startRank: Long, endRank: Long)    = ???
                }
              }

              runTestUnsafe(TestData()) {
                TestEnvironment(
                  testDataRef,
                  maxParallelBlockDownloads = 1,
                  mkBackend = faultyBackend,
                  clientCert = stubCert.some
                ).use { stub =>
                  val req = GetBlockChunkedRequest(blockHash = hash)
                  for {
                    r1 <- stub.getBlockChunked(req).toListL.attempt
                    r2 <- stub.getBlockChunked(req).toListL.attempt
                    r3 <- stub.getBlockChunked(req).toListL.attempt
                  } yield {
                    r1.isLeft shouldBe true
                    r1.left.get match {
                      case ex: io.grpc.StatusRuntimeException =>
                        ex.getStatus.getCode shouldBe io.grpc.Status.Code.INTERNAL
                      case ex =>
                        fail(s"Unexpected error: $ex")
                    }
                    // If the semaphore wasn't freed this would time out.
                    r2.isLeft shouldBe true
                    r3.isLeft shouldBe true
                    r3.left.get match {
                      case NotFound(msg) =>
                        msg shouldBe s"Block ${Base16.encode(hash.toByteArray)} could not be found."
                      case ex =>
                        fail(s"Unexpected error: $ex")
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  object StreamBlockSummariesSpec extends WordSpecLike {
    implicit val config                         = PropertyCheckConfiguration(minSuccessful = 5)
    implicit val hashGen: Arbitrary[ByteString] = Arbitrary(genHash)
    implicit val consensusConfig                = ConsensusConfig()

    "streamBlockSummaries" when {
      "called with a mix of known and unknown hashes" should {
        "return a stream of the known summaries" in {
          val genTestCase = for {
            summaries <- arbitrary[Set[BlockSummary]]
            known     <- Gen.someOf(summaries.map(_.blockHash))
            other     <- arbitrary[Set[ByteString]]
          } yield (summaries, known, other)

          forAll(genTestCase) {
            case (summaries, known, other) =>
              runTestUnsafe(TestData(summaries = summaries.toSeq)) {
                val req =
                  StreamBlockSummariesRequest(
                    // Sending some unknown ones to see that it won't choke on them.
                    blockHashes = (known ++ other).toSeq
                  )
                stub.streamBlockSummaries(req).toListL.map { found =>
                  found.map(_.blockHash) should contain theSameElementsAs known
                }
              }
          }
        }
      }
    }
  }

  object StreamAncestorBlockSummariesSpec extends WordSpecLike {
    implicit val config                         = PropertyCheckConfiguration(minSuccessful = 25)
    implicit val hashGen: Arbitrary[ByteString] = Arbitrary(genHash)
    implicit val consensusConfig                = ConsensusConfig()

    def elders(summary: BlockSummary): Seq[ByteString] =
      summary.parentHashes ++
        summary.justifications.map(_.latestBlockHash)

    /** Collect the ancestors of a hash and return their minimum distance to the target. */
    def collectAncestors(
        summaries: Map[ByteString, BlockSummary],
        target: ByteString,
        maxDepth: Int
    ): Map[ByteString, Int] = {
      def loop(visited: Map[ByteString, Int], hash: ByteString, depth: Int): Map[ByteString, Int] =
        if (depth > maxDepth)
          visited
        else {
          val summary     = summaries(hash)
          val nextVisited = visited + (summary.blockHash -> depth)
          elders(summary).foldLeft(nextVisited) {
            case (visited, hash) if visited.contains(hash) && visited(hash) <= depth + 1 =>
              visited
            case (visited, hash) =>
              loop(visited, hash, depth + 1)
          }
        }
      loop(Map.empty, target, 0)
    }

    /** Collect the ancestors of all targets and return the minimum distance to the nearest target. */
    def collectAncestorsForMany(
        summaries: Map[ByteString, BlockSummary],
        targets: Seq[ByteString],
        maxDepth: Int
    ): Map[ByteString, Int] =
      targets flatMap { target =>
        collectAncestors(summaries, target, maxDepth).toSeq
      } groupBy {
        _._1
      } mapValues {
        _.map(_._2).min
      }

    /** Map of every parent to the children they have. */
    def collectChildren(dag: Vector[BlockSummary]): Map[ByteString, Seq[ByteString]] = {
      val ecs = for {
        child <- dag
        elder <- elders(child)
      } yield (elder -> child.blockHash)

      ecs
        .groupBy(_._1)
        .mapValues(_.map(_._2))
    }

    // Wrap the the dependant data into a case class, otherwise if we just use a tuple for example
    // and there's an error, ScalaCheck can shrink down the input to where it becomes nonsensical.
    // For example if we select N targets from the DAG it can narrow down the data to where the DAG
    // has 0 items but there are non-zero targets.
    case class TestCase[T](dag: Vector[BlockSummary], data: T) {
      lazy val summaries = TestData(summaries = dag).summaries
    }

    class TestFixture[T](gen: Gen[TestCase[T]], test: TestCase[T] => Task[Unit]) {
      forAll(gen) { tc =>
        runTestUnsafe(TestData(summaries = tc.dag)) {
          test(tc)
        }
      }
    }
    object TestFixture {
      def apply[T](gen: Gen[TestCase[T]])(test: TestCase[T] => Task[Unit]) =
        new TestFixture[T](gen, test)
    }

    "streamAncestorBlockSummaries" when {
      "called with unknown target hashes" should {
        "return an empty stream" in {
          forAll(genSummaryDagFromGenesis, arbitrary[List[ByteString]]) { (dag, targets) =>
            runTestUnsafe(TestData(summaries = dag)) {
              val req = StreamAncestorBlockSummariesRequest(targetBlockHashes = targets)

              stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
                ancestors shouldBe empty
              }
            }
          }
        }
      }

      "called with a (default) depth of 0" should {
        val genTestCase = for {
          dag     <- genSummaryDagFromGenesis
          targets <- Gen.someOf(dag)
          req     = StreamAncestorBlockSummariesRequest(targetBlockHashes = targets.map(_.blockHash))
        } yield TestCase(dag, req)

        "return just the target summaries" in TestFixture(genTestCase) {
          case TestCase(_, req) =>
            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              ancestors.map(_.blockHash) should contain theSameElementsAs req.targetBlockHashes
            }
        }
      }

      "called with a depth of 1" should {
        val genTestCase = for {
          dag     <- genSummaryDagFromGenesis
          targets <- Gen.someOf(dag)
          req = StreamAncestorBlockSummariesRequest(
            targetBlockHashes = targets.map(_.blockHash),
            maxDepth = 1
          )
        } yield TestCase(dag, req)

        "return the targets and their parents + justifications" in TestFixture(genTestCase) {
          case tc @ TestCase(_, req) =>
            val expected = (
              req.targetBlockHashes ++
                req.targetBlockHashes.flatMap { t =>
                  elders(tc.summaries(t))
                }
            ).toSet

            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              ancestors.map(_.blockHash) should contain theSameElementsAs expected
            }
        }
      }

      "called with a depth of -1" should {
        val genTestCase = for {
          dag     <- genSummaryDagFromGenesis
          targets <- Gen.choose(1, dag.size).flatMap(Gen.pick(_, dag))
          req = StreamAncestorBlockSummariesRequest(
            targetBlockHashes = targets.map(_.blockHash),
            maxDepth = -1
          )
        } yield TestCase(dag, req)

        "return everything back to the genesis" in TestFixture(genTestCase) {
          case TestCase(dag, req) =>
            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              ancestors should contain(dag.head)
            }
        }
      }

      "called with a single target and a given maximum depth value" should {
        val genTestCase = for {
          dag    <- genSummaryDagFromGenesis
          target <- Gen.oneOf(dag)
          depth  <- Gen.choose(0, dag.size)
          req = StreamAncestorBlockSummariesRequest(
            targetBlockHashes = Seq(target.blockHash),
            maxDepth = depth
          )
        } yield TestCase(dag, req)

        "return all ancestors of the target up to that depth in reverse breadth first order" in TestFixture(
          genTestCase
        ) {
          case tc @ TestCase(_, req) =>
            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              val targetHash = req.targetBlockHashes.head
              val depths =
                collectAncestors(
                  tc.summaries,
                  targetHash,
                  req.maxDepth
                )

              val ancestorHashes = ancestors.map(_.blockHash)
              ancestorHashes.head shouldBe targetHash
              ancestorHashes should contain theSameElementsAs depths.keySet
              // The order of elements in the same rank is not specified,
              // but we can check for partial ordering.
              Inspectors.forAll(ancestorHashes.init zip ancestorHashes.tail) {
                case (a, b) =>
                  depths(a) should be <= depths(b)
              }
            }
        }
      }

      "called with many targets and maximum depth" should {
        val genTestCase = for {
          dag      <- genSummaryDagFromGenesis
          targets  <- Gen.someOf(dag)
          maxDepth <- Gen.choose(0, dag.size)
          req = StreamAncestorBlockSummariesRequest(
            targetBlockHashes = targets.map(_.blockHash),
            maxDepth = maxDepth
          )
        } yield TestCase(dag, req)

        "start with the targets" in TestFixture(genTestCase) {
          case TestCase(_, req) =>
            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              val targetHashes   = req.targetBlockHashes
              val startingHashes = ancestors.map(_.blockHash).take(targetHashes.size)
              startingHashes should contain theSameElementsAs targetHashes
            }
        }

        "return results in the same order regardless of depth" in TestFixture(
          for {
            tc    <- genTestCase
            depth <- Gen.choose(0, tc.data.maxDepth)
          } yield TestCase(tc.dag, tc.data -> depth)
        ) {
          case TestCase(_, (req1, depth)) =>
            val req2 = req1.copy(maxDepth = depth)
            for {
              a1 <- stub
                     .streamAncestorBlockSummaries(req1)
                     .map(_.blockHash)
                     .toListL
              a2 <- stub
                     .streamAncestorBlockSummaries(req2)
                     .map(_.blockHash)
                     .toListL
            } yield {
              a1.take(depth) should contain theSameElementsInOrderAs a2.take(depth)
            }
        }

        "return all ancestors up to that depth from any of the targets" in TestFixture(genTestCase) {
          case tc @ TestCase(_, req) =>
            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              val ancestorHashes = ancestors.map(_.blockHash)
              val depthsFromNearest =
                collectAncestorsForMany(
                  tc.summaries,
                  req.targetBlockHashes,
                  req.maxDepth
                )

              ancestorHashes should contain theSameElementsAs depthsFromNearest.keySet
              // Check partial ordering
              if (ancestorHashes.nonEmpty) {
                Inspectors.forAll(ancestorHashes.init zip ancestorHashes.tail) {
                  case (a, b) =>
                    depthsFromNearest(a) should be <= depthsFromNearest(b)
                }
              }
            }
        }
      }

      "called with some known hashes" should {
        val genTestCase = for {
          dag      <- genSummaryDagFromGenesis
          targets  <- Gen.someOf(dag)
          knowns   <- Gen.someOf(dag)
          maxDepth <- Gen.choose(0, dag.size)
          req = StreamAncestorBlockSummariesRequest(
            targetBlockHashes = targets.map(_.blockHash),
            knownBlockHashes = knowns.map(_.blockHash),
            maxDepth = maxDepth
          )
        } yield TestCase(dag, req)

        "stop traversing ancestors beyond the known hashes" in TestFixture(genTestCase) {
          case TestCase(dag, req) =>
            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              val targetHashes   = req.targetBlockHashes.toSet
              val knownHashes    = req.knownBlockHashes.toSet
              val ancestorHashes = ancestors.map(_.blockHash).toSet
              val childHashes    = collectChildren(dag)

              // Targets should be returned even if known.
              Inspectors.forAll(req.targetBlockHashes) { targetHash =>
                ancestorHashes should contain(targetHash)
              }

              // Check that if we see a parent of a known hash then we must have arrived
              // at that parent through another, previously unknown child.
              Inspectors.forAll(knownHashes) { knownHash =>
                val eldersOfKnown =
                  elders(testDataRef.get.summaries(knownHash)).filter(ancestorHashes)
                Inspectors.forAll(eldersOfKnown) { elderHash =>
                  assert {
                    targetHashes(elderHash) ||
                    childHashes(elderHash).exists { otherChild =>
                      ancestorHashes(otherChild) && !knownHashes(otherChild)
                    }
                  }
                }
              }
            }
        }

        "return the known blocks if they are within the maximum depth" in TestFixture(genTestCase) {
          case tc @ TestCase(_, req0) =>
            // Just using 1 known so we know that we won't stop before reaching it due to
            // other known ancestors on the path.
            val req = req0.copy(knownBlockHashes = req0.knownBlockHashes.take(1))

            stub.streamAncestorBlockSummaries(req).toListL.map { ancestors =>
              val ancestorHashes = ancestors.map(_.blockHash).toSet
              val isReachable = collectAncestorsForMany(
                tc.summaries,
                req.targetBlockHashes,
                req.maxDepth
              ).keySet

              Inspectors.forAll(req.knownBlockHashes) { knownHash =>
                if (isReachable(knownHash)) {
                  ancestorHashes should contain(knownHash)
                } else {
                  ancestorHashes should not contain (knownHash)
                }
              }
            }
        }
      }
    }
  }

  object StreamLatestMessagesSpec extends WordSpecLike {
    implicit val config          = PropertyCheckConfiguration(minSuccessful = 5)
    implicit val consensusConfig = ConsensusConfig()

    "streamLatestMessages" should {
      "return latest messages in the DAG" in {
        forAll(genSummaryDagFromGenesis) { dag =>
          // ProtoUtil.removeRedundantJustifications is not available here.
          val expected = dag
            .groupBy(_.getHeader.validatorPublicKey)
            .values
            .flatten
            .map(s => Block.Justification(s.getHeader.validatorPublicKey, s.blockHash))
            .toList
          runTestUnsafe(TestData(summaries = dag)) {
            TestEnvironment(testDataRef).use { stub =>
              stub.streamLatestMessages(StreamLatestMessagesRequest()).toListL map { res =>
                res should contain theSameElementsAs expected
              }
            }
          }
        }
      }
    }
  }

  object NewBlocksSpec extends WordSpecLike with AuthSpec {
    override def rpcName: String = "newBlocks"

    override def query
        : (Option[Node], List[ByteString]) => GossipingGrpcMonix.GossipServiceStub => Task[Unit] =
      (maybeSender, blockHashes) =>
        client => client.newBlocks(NewBlocksRequest(maybeSender, blockHashes)).void

    override def ignoreSender: Boolean = false

    def expectError(
        req: NewBlocksRequest,
        client: GossipingGrpcMonix.GossipServiceStub = stub
    )(pf: PartialFunction[Throwable, Unit]): Task[Unit] =
      client.newBlocks(req).attempt.map { res =>
        res.isLeft shouldBe true
        pf.lift(res.left.get) getOrElse {
          fail(s"Unexpected error: ${res.left.get}")
        }
      }

    "newBlocks" when {
      "called with a valid sender" when {
        implicit val config = PropertyCheckConfiguration(minSuccessful = 5)

        "receives no previously unknown blocks" should {
          "return false and not download anything" in {
            if (sys.env.contains("DRONE_BRANCH")) {
              cancel("On Drone it sometimes returns `true` for some inexplicable reason.")
            }

            val genTestCase = for {
              dag  <- genBlockDagFromGenesis
              node <- arbitrary[Node].map(_.withId(stubCert.keyHash))
              n    <- Gen.choose(0, dag.size)
            } yield (dag, node, n)

            forAll(genTestCase) {
              case (dag, node, n) =>
                runTestUnsafe(TestData(blocks = dag)) {
                  val req = NewBlocksRequest()
                    .withSender(node)
                    .withBlockHashes(dag.takeRight(n).map(_.blockHash))

                  // If `newBlocks` called any of the default empty mock classes they would throw.
                  stub.newBlocks(req) map { res =>
                    res.isNew shouldBe false
                  }
                }
            }
          }
        }

        "receives new blocks" should {
          "download the new ones" in {
            val genTestCase = for {
              dag  <- genBlockDagFromGenesis
              node <- arbitrary[Node].map(_.withId(stubCert.keyHash))
              k    <- Gen.choose(1, dag.size / 2)
              n    <- Gen.choose(1, k)
            } yield (dag, node, k, n)

            forAll(genTestCase) {
              case (dag, node, k, n) =>
                val knownBlocks   = dag.take(k)
                val unknownBlocks = dag.drop(k)
                val newBlocks     = unknownBlocks.takeRight(n)

                // Only pass the known part to the backend of the service.
                runTestUnsafe(TestData(blocks = knownBlocks)) {

                  val req = NewBlocksRequest()
                    .withSender(node)
                    .withBlockHashes(newBlocks.map(_.blockHash))

                  // Pretend to sync the DAG and return the unknown part in topological order.
                  val synchronizer = new Synchronizer[Task] {
                    def syncDag(source: Node, targetBlockHashes: Set[ByteString]) = {
                      source shouldBe node
                      targetBlockHashes should contain theSameElementsAs newBlocks.map(_.blockHash)
                      val dag = unknownBlocks.map(summaryOf(_))
                      // Delay the return of the DAG a little bit so we can assert that the call
                      // to `newBlocks` returns before all the syncing and downloading is finished.
                      Task.now(dag.asRight[SyncError]).delayResult(250.millis)
                    }
                    def downloaded(blockHash: ByteString) = Task.unit
                  }

                  val downloadManager = new DownloadManager[Task] {
                    @volatile var scheduled = Vector.empty[ByteString]
                    def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean) = {
                      source shouldBe node
                      unknownBlocks.map(_.blockHash) should contain(summary.blockHash)
                      if (relay) {
                        newBlocks.map(_.blockHash) should contain(summary.blockHash)
                      } else {
                        newBlocks.map(_.blockHash) should not contain (summary.blockHash)
                      }
                      synchronized {
                        scheduled = scheduled :+ summary.blockHash
                      }
                      Task.now(Task.unit)
                    }
                  }

                  TestEnvironment(
                    testDataRef,
                    clientCert = Some(stubCert),
                    synchronizer = synchronizer,
                    downloadManager = downloadManager
                  ).use { stub =>
                    stub.newBlocks(req) map { res =>
                      val unknownHashes = unknownBlocks.map(_.blockHash)
                      res.isNew shouldBe true
                      eventually {
                        downloadManager.scheduled should contain theSameElementsInOrderAs unknownHashes
                      }
                    }
                  }
                }
            }
          }
        }
      }
    }
  }

  object GenesisApprovalSpec extends WordSpecLike {
    implicit val hashGen: Arbitrary[ByteString] = Arbitrary(genHash)

    class MockGenesisApprover() extends GenesisApprover[Task] {
      override def getCandidate: Task[Either[ServiceError, GenesisCandidate]] =
        Task.now(Left(Unavailable("Come back later.")))
      override def addApproval(
          blockHash: ByteString,
          approval: Approval
      ): Task[Either[ServiceError, Boolean]] =
        Task.now(Left(Unavailable("Come back later.")))
      override def awaitApproval = ???
    }

    def testWithApprover(
        genesisApprover: GenesisApprover[Task]
    )(f: GossipingGrpcMonix.GossipServiceStub => Task[Unit]) =
      runTestUnsafe(TestData()) {
        TestEnvironment(testDataRef, genesisApprover = genesisApprover).use(f(_))
      }

    def expectUnavailable(msg: String)(call: Task[_]) =
      call.attempt.map {
        case Left(ex) =>
          ex shouldBe an[io.grpc.StatusRuntimeException]
          ex.asInstanceOf[io.grpc.StatusRuntimeException]
            .getStatus
            .getCode shouldBe io.grpc.Status.Code.UNAVAILABLE
          ex.getMessage should include(msg)
        case other =>
          fail(s"Expected Unavailable; got $other")
      } map (_ => ())

    "getGenesisCandidate" when {
      "there is no candidate yet" should {
        "return the error from the approver" in {
          testWithApprover(new MockGenesisApprover()) { stub =>
            expectUnavailable("Come back later.") {
              stub.getGenesisCandidate(GetGenesisCandidateRequest())
            }
          }
        }
      }
      "there is a candidate available" should {
        "return the candidate from the approver" in {
          val candidate = GenesisCandidate()
            .withBlockHash(sample(arbitrary[ByteString]))

          testWithApprover(new MockGenesisApprover() {
            override def getCandidate = Task.now(Right(candidate))
          }) { stub =>
            stub.getGenesisCandidate(GetGenesisCandidateRequest()) map { res =>
              res shouldBe candidate
            }
          }
        }
      }
    }

    "addApproval" when {
      "the approver rejects the approval" should {
        "return the error from the approver" in {
          testWithApprover(new MockGenesisApprover()) { stub =>
            expectUnavailable("Come back later.") {
              stub.getGenesisCandidate(GetGenesisCandidateRequest())
            }
          }
        }
      }
      "the approver accepts the approval" should {
        "return empty" in {
          testWithApprover(new MockGenesisApprover() {
            override def addApproval(blockHash: ByteString, approval: Approval) =
              Task.now(Right(true))
          }) { stub =>
            stub.addApproval(AddApprovalRequest()).map { res =>
              res shouldBe (com.google.protobuf.empty.Empty())
            }
          }
        }
      }
    }
  }

  object StreamDagSliceBlockSummariesSpec extends WordSpecLike {
    implicit val config                         = PropertyCheckConfiguration(minSuccessful = 10)
    implicit val hashGen: Arbitrary[ByteString] = Arbitrary(genHash)
    implicit val consensusConfig = ConsensusConfig(
      dagSize = 10
    )

    "streamDagSliceBlockSummariesSpec" when {
      "called with a min and max rank" should {
        /* Abstracts over streamDagSlice RPC test, parameters are dag, start and end ranks */
        def test(task: (Vector[BlockSummary], Int, Int) => Task[Unit]): Unit =
          forAll(genSummaryDagFromGenesis) { dag =>
            val minRank = dag.map(_.rank).min.toInt
            val maxRank = dag.map(_.rank).max.toInt

            val startGen: Gen[Int] = Gen.choose(minRank, math.max(maxRank - 1, minRank))
            val endGen: Gen[Int]   = startGen.flatMap(start => Gen.choose(start, maxRank))

            forAll(startGen, endGen) { (startRank, endRank) =>
              runTestUnsafe(TestData(dag))(task(dag, startRank, endRank))
            }
          }

        "return only valid ranks in increasing order" in {
          if (sys.env.contains("DRONE_BRANCH")) {
            cancel("NODE-1036")
          }
          test {
            case (dag, startRank, endRank) =>
              val req = StreamDagSliceBlockSummariesRequest(
                startRank = startRank,
                endRank = endRank
              )
              for {
                res <- stub
                        .streamDagSliceBlockSummaries(req)
                        .toListL
              } yield {
                val expected = dag.filter(s => s.rank >= startRank && s.rank <= endRank)
                // Returned slice must be increasing order by rank,
                // but it may differ from expected if there are multiple summaries for the same rank.
                // We don't care about it and checking only ranks
                Inspectors.forAll(res.zip(expected)) {
                  case (a, b) =>
                    assert(a.rank == b.rank)
                }
              }
          }
        }
        "should not return the same summary multiple times in a slice" in {
          test {
            case (_, startRank, endRank) =>
              val req = StreamDagSliceBlockSummariesRequest(
                startRank = startRank,
                endRank = endRank
              )
              for {
                res <- stub
                        .streamDagSliceBlockSummaries(req)
                        .toListL
              } yield {
                Inspectors.forAll(res.groupBy(_.blockHash).toList) {
                  case (_, summaries) =>
                    assert(summaries.size == 1)
                }
              }
          }
        }
      }
    }
  }
}

object GrpcGossipServiceSpec extends TestRuntime with ArbitraryConsensusAndComm {
  // Specify small enough chunks so we see lots of messages and can tell that it terminated early.
  val DefaultMaxChunkSize = 10 * 1024

  def md5(data: Array[Byte]): String = {
    val md = java.security.MessageDigest.getInstance("MD5")
    new String(md.digest(data))
  }

  def summaryOf(block: Block): BlockSummary =
    BlockSummary()
      .withBlockHash(block.blockHash)
      .withHeader(block.getHeader)
      .withSignature(block.getSignature)

  trait TestData {
    def summaries: Map[ByteString, BlockSummary]
    def blocks: Map[ByteString, Block]
  }

  object TestData {
    val empty = TestData()

    def fromBlock(block: Block) = TestData(blocks = Seq(block))

    def apply(
        summaries: Seq[BlockSummary] = Seq.empty,
        blocks: Seq[Block] = Seq.empty
    ): TestData = {
      val ss = summaries
      val bs = blocks
      new TestData {
        val summaries = ss.groupBy(_.blockHash).mapValues(_.head)
        val blocks    = bs.groupBy(_.blockHash).mapValues(_.head)
      }
    }
  }

  object TestEnvironment {
    private val emptySynchronizer = new Synchronizer[Task] {
      def syncDag(source: Node, targetBlockHashes: Set[ByteString]) = ???
      def downloaded(blockHash: ByteString): Task[Unit]             = ???
    }
    private val emptyDownloadManager = new DownloadManager[Task] {
      def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean) = ???
    }
    private val emptyGenesisApprover = new GenesisApprover[Task] {
      def getCandidate                                           = ???
      def addApproval(blockHash: ByteString, approval: Approval) = ???
      def awaitApproval                                          = ???
    }

    private def defaultBackend(testDataRef: AtomicReference[TestData]) =
      new GossipServiceServer.Backend[Task] {
        def hasBlock(blockHash: ByteString) =
          Task.delay(testDataRef.get.blocks.contains(blockHash))
        def getBlock(blockHash: ByteString) =
          Task.delay(testDataRef.get.blocks.get(blockHash))
        def getBlockSummary(blockHash: ByteString) =
          Task.delay(testDataRef.get.summaries.get(blockHash))
        def latestMessages: Task[Set[Block.Justification]] =
          Task.delay(
            testDataRef.get.summaries.values
              .map(bs => Block.Justification(bs.getHeader.validatorPublicKey, bs.blockHash))
              .toSet
          )

        def dagTopoSort(startRank: Long, endRank: Long) =
          Iterant
            .liftF(
              Task.delay(
                testDataRef
                  .get()
                  .summaries
                  .values
                  .filter(s => s.rank >= startRank && s.rank <= endRank)
                  .toList
                  .sortBy(_.rank)
              )
            )
            .flatMap(Iterant.fromSeq[Task, BlockSummary])
      }

    implicit val chainId: ByteString = sample(genHash)

    def apply(
        testDataRef: AtomicReference[TestData],
        clientCert: Option[TestCert] = Some(TestCert.generate),
        clientAuth: ClientAuth = ClientAuth.REQUIRE,
        maxParallelBlockDownloads: Int = 100,
        blockChunkConsumerTimeout: FiniteDuration = 10.seconds,
        synchronizer: Synchronizer[Task] = emptySynchronizer,
        downloadManager: DownloadManager[Task] = emptyDownloadManager,
        genesisApprover: GenesisApprover[Task] = emptyGenesisApprover,
        rateLimiter: RateLimiter[Task, ByteString] = RateLimiter.noOp,
        mkBackend: AtomicReference[TestData] => GossipServiceServer.Backend[Task] = defaultBackend
    )(
        implicit
        oi: ObservableIterant[Task],
        scheduler: Scheduler
    ): Resource[Task, GossipingGrpcMonix.GossipServiceStub] = {
      val port             = getFreePort
      val serverCert       = TestCert.generate
      implicit val metrics = new Metrics.MetricsNOP[Task]
      implicit val logTask = Log.NOPLog[Task]
      implicit val logId   = Log.NOPLog[Id]

      val serverR = GrpcServer[Task](
        port,
        services = List(
          (scheduler: Scheduler) =>
            GossipServiceServer[Task](
              backend = mkBackend(testDataRef),
              synchronizer = synchronizer,
              downloadManager = downloadManager,
              genesisApprover = genesisApprover,
              maxChunkSize = DefaultMaxChunkSize,
              maxParallelBlockDownloads = maxParallelBlockDownloads
            ) map { gss =>
              val svc = GrpcGossipService
                .fromGossipService(gss, rateLimiter, chainId, blockChunkConsumerTimeout)
              GossipingGrpcMonix.bindService(svc, scheduler)
            }
        ),
        interceptors = List(
          // For now the AuthInterceptor rejects calls without a certificate.
          Option(new AuthInterceptor()).filter(_ => clientAuth == ClientAuth.REQUIRE),
          Some(ErrorInterceptor.default)
        ).flatten,
        // If the server is using SSL then we can't connect to it using `.usePlaintext`
        // on the client channel, it would get UNAVAILABLE.
        // Conversely, if the server isn't using SSL, the client can't do so either.
        sslContext = Some(
          SslContexts.forServer(
            serverCert.cert,
            serverCert.key,
            clientAuth
          )
        )
      )

      def sslContext =
        clientCert.fold(
          SslContexts.forClientUnauthenticated
        )(cc => SslContexts.forClient(cc.cert, cc.key))

      val channelR =
        Resource.make(
          Task.delay {
            NettyChannelBuilder
              .forAddress("localhost", port)
              .executor(scheduler)
              .negotiationType(NegotiationType.TLS)
              .sslContext(sslContext)
              .overrideAuthority(serverCert.id) // So that "localhost" isn't rejected, as it's not what the certificate is for.
              .build
          }
        )(
          channel =>
            Task.delay {
              channel.shutdown()
            }
        )

      for {
        _       <- serverR
        channel <- channelR
        stub    = new GossipingGrpcMonix.GossipServiceStub(channel)
      } yield stub
    }

  }

  case class TestCert(cert: String, key: String, id: String, keyHash: ByteString)

  object TestCert {
    def generate = {
      val pair = CertificateHelper.generateKeyPair(useNonBlockingRandom = true)
      val cert = CertificateHelper.generate(pair)
      val addr = CertificateHelper.publicAddress(pair.getPublic).get
      TestCert(
        cert = CertificatePrinter.print(cert),
        key = CertificatePrinter.printPrivateKey(pair.getPrivate),
        id = Base16.encode(addr),
        keyHash = ByteString.copyFrom(addr)
      )
    }
  }
}
