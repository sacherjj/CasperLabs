package io.casperlabs.comm.gossiping

import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.shared.Compression
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.util.{CertificateHelper, CertificatePrinter}
import io.casperlabs.comm.ServiceError.{NotFound, Unauthenticated}
import io.casperlabs.comm.TestRuntime
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.grpc.{AuthInterceptor, GrpcServer, SslContexts}
import io.grpc.netty.{NegotiationType, NettyChannelBuilder}
import io.netty.handler.ssl.{ClientAuth, SslContext}
import java.util.concurrent.atomic.AtomicReference
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import monix.reactive.Observable
import monix.tail.Iterant
import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll, PropertyCheckConfiguration}
import org.scalacheck.{Arbitrary, Gen}, Arbitrary.arbitrary
import scala.concurrent.duration._

class GrpcGossipServiceSpec
    extends refspec.RefSpecLike
    with Eventually
    with Matchers
    with BeforeAndAfterAll
    with ArbitraryConsensus {

  import GrpcGossipServiceSpec._
  import Scheduler.Implicits.global

  implicit val consensusConfig = ConsensusConfig()

  // Test data that we can set in each test.
  val testDataRef = new AtomicReference(TestData.empty)
  // Set up the server and client once, to be shared, to make tests faster.
  var stub: GossipingGrpcMonix.GossipServiceStub = _
  var shutdown: Task[Unit]                       = _

  override def beforeAll() =
    TestEnvironment(testDataRef).allocated.foreach {
      case (stub, shutdown) =>
        this.stub = stub
        this.shutdown = shutdown
    }

  override def afterAll() =
    shutdown.runSyncUnsafe(10.seconds)

  def runTestUnsafe(testData: TestData)(test: Task[Unit]): Unit = {
    testDataRef.set(testData)
    test.runSyncUnsafe(5.seconds)
  }

  override def nestedSuites = Vector(
    GetBlockChunkedSpec,
    StreamBlockSummariesSpec,
    StreamAncestorBlockSummariesSpec,
    NewBlocksSpec
  )

  object GetBlockChunkedSpec extends WordSpecLike {
    // Just want to test with random blocks; variety doesn't matter.
    implicit val propCheckConfig = PropertyCheckConfiguration(minSuccessful = 1)
    implicit val patienceConfig  = PatienceConfig(1.second, 100.millis)

    "getBlocksChunked" when {
      "no compression is supported" should {
        "return a stream of uncompressed chunks" in {
          forAll { (block: Block) =>
            runTestUnsafe(TestData.fromBlock(block)) {
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
          forAll { (block: Block) =>
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
        def testChunkSize(block: Block, requestedChunkSize: Int, expectedChunkSize: Int): Unit =
          runTestUnsafe(TestData.fromBlock(block)) {
            val req =
              GetBlockChunkedRequest(blockHash = block.blockHash, chunkSize = requestedChunkSize)
            stub.getBlockChunked(req).toListL.map { chunks =>
              Inspectors.forAll(chunks.tail.init) { chunk =>
                chunk.getData.size shouldBe expectedChunkSize
              }
              chunks.last.getData.size should be <= expectedChunkSize
            }
          }

        "it is less then the maximum" should {
          "use the requested chunk size" in {
            forAll { (block: Block) =>
              val smallChunkSize = DefaultMaxChunkSize / 2
              testChunkSize(block, smallChunkSize, smallChunkSize)
            }
          }
        }

        "bigger than the maximum" should {
          "use the default chunk size" in {
            forAll { (block: Block) =>
              val bigChunkSize = DefaultMaxChunkSize * 2
              testChunkSize(block, bigChunkSize, DefaultMaxChunkSize)
            }
          }
        }
      }

      "block cannot be found" should {
        "return NOT_FOUND" in {
          forAll(genHash) { (hash: ByteString) =>
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
          forAll { (block: Block) =>
            runTestUnsafe(TestData.fromBlock(block)) {
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

              TestEnvironment(testDataRef)(oi, scheduler).use { stub =>
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
    }
  }

  object StreamBlockSummariesSpec extends WordSpecLike {
    implicit val config                         = PropertyCheckConfiguration(minSuccessful = 5)
    implicit val hashGen: Arbitrary[ByteString] = Arbitrary(genHash)

    "streamBlockSummaries" when {
      "called with a mix of known and unknown hashes" should {
        "return a stream of the known summaries" in {
          val genTestData = for {
            summaries <- arbitrary[Set[BlockSummary]]
            known     <- Gen.someOf(summaries.map(_.blockHash))
            other     <- arbitrary[Set[ByteString]]
          } yield (summaries, known, other)

          forAll(genTestData) {
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

    def elders(summary: BlockSummary): Seq[ByteString] =
      summary.getHeader.parentHashes ++
        summary.getHeader.justifications.map(_.latestBlockHash)

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
          forAll(genDag, arbitrary[List[ByteString]]) { (dag, targets) =>
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
          dag     <- genDag
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
          dag     <- genDag
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
          dag     <- genDag
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
          dag    <- genDag
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
          dag      <- genDag
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
          dag      <- genDag
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

  object NewBlocksSpec extends WordSpecLike {
    implicit val config                         = PropertyCheckConfiguration(minSuccessful = 1)
    implicit val hashGen: Arbitrary[ByteString] = Arbitrary(genHash)

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
      "called without a sender" should {
        "return UNAUTHENTICATED" in {
          forAll(arbitrary[List[ByteString]]) { blockHashes =>
            runTestUnsafe(TestData()) {
              expectError(
                NewBlocksRequest(sender = None, blockHashes = blockHashes)
              ) {
                case Unauthenticated(msg) =>
                  msg shouldBe "Sender cannot be empty."
              }
            }
          }
        }
      }
      "called with a sender whose ID doesn't match its SSL public key" should {
        "return UNAUTHENTICATED" in {
          forAll(arbitrary[List[ByteString]], arbitrary[Node]) { (blockHashes, sender) =>
            runTestUnsafe(TestData()) {
              expectError(
                NewBlocksRequest(sender = Some(sender), blockHashes = blockHashes)
              ) {
                case Unauthenticated(msg) =>
                  msg shouldBe "Sender doesn't match public key."
              }
            }
          }
        }
      }
      "called without an SSL certificate" when {

        def expectErrorWithAnonymous(clientAuth: ClientAuth)(pf: PartialFunction[Throwable, Unit]) =
          forAll(arbitrary[List[ByteString]], arbitrary[Node]) { (blockHashes, sender) =>
            runTestUnsafe(TestData()) {
              TestEnvironment(testDataRef, anonymous = true, clientAuth = clientAuth).use {
                anonymousStub =>
                  expectError(
                    NewBlocksRequest(sender = Some(sender), blockHashes = blockHashes),
                    client = anonymousStub
                  )(pf)
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

object GrpcGossipServiceSpec extends TestRuntime {
  // Specify small enough chunks so we see lots of messages and can tell that it terminated early.
  val DefaultMaxChunkSize              = 10 * 1024
  val DefaultMaxParallelBlockDownloads = 100

  def md5(data: Array[Byte]): String = {
    val md = java.security.MessageDigest.getInstance("MD5")
    new String(md.digest(data))
  }

  trait TestData {
    val summaries: Map[ByteString, BlockSummary]
    val blocks: Map[ByteString, Block]
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
    def apply(
        testData: AtomicReference[TestData],
        anonymous: Boolean = false,
        clientAuth: ClientAuth = ClientAuth.REQUIRE
    )(
        implicit
        oi: ObservableIterant[Task],
        scheduler: Scheduler
    ): Resource[Task, GossipingGrpcMonix.GossipServiceStub] = {
      val port = getFreePort

      val serverCert = TestCert.generate

      val serverR = GrpcServer[Task](
        port,
        services = List(
          (scheduler: Scheduler) =>
            GossipServiceServer[Task](
              getBlockSummary = hash => Task.now(testData.get.summaries.get(hash)),
              getBlock = hash => Task.now(testData.get.blocks.get(hash)),
              maxChunkSize = DefaultMaxChunkSize,
              maxParallelBlockDownloads = DefaultMaxParallelBlockDownloads
            ) map { gss =>
              val svc = GrpcGossipService.fromGossipService(gss)
              GossipingGrpcMonix.bindService(svc, scheduler)
            }
        ),
        interceptors = List(
          // For now the AuthInterceptor rejects calls without a certificate.
          Option(new AuthInterceptor()).filter(_ => clientAuth == ClientAuth.REQUIRE)
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
        if (anonymous) {
          SslContexts.forClientUnauthenticated
        } else {
          val clientCert = TestCert.generate
          SslContexts.forClient(clientCert.cert, clientCert.key)
        }

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

    case class TestCert(cert: String, key: String, id: String)

    object TestCert {
      def generate = {
        val pair  = CertificateHelper.generateKeyPair(useNonBlockingRandom = true)
        val cert  = CertificateHelper.generate(pair)
        val certS = CertificatePrinter.print(cert)
        val keyS  = CertificatePrinter.printPrivateKey(pair.getPrivate)
        val idS =
          CertificateHelper.publicAddress(pair.getPublic).map(Base16.encode(_)).getOrElse("local")
        TestCert(certS, keyS, idS)
      }
    }
  }
}
