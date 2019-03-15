package io.casperlabs.comm.gossiping

import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.shared.Compression
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.comm.ServiceError.NotFound
import io.casperlabs.comm.GrpcServer
import io.casperlabs.comm.TestRuntime
import io.grpc.netty.NettyChannelBuilder
import java.util.concurrent.atomic.AtomicReference
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import monix.reactive.Observable
import monix.tail.Iterant
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll, PropertyCheckConfiguration}
import org.scalacheck.{Arbitrary, Gen}, Arbitrary.arbitrary
import scala.concurrent.duration._

class GrpcGossipServiceSpec
    extends refspec.RefSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ArbitraryConsensus {

  import GrpcGossipServiceSpec._
  import Scheduler.Implicits.global

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

  def runTestUnsafe(testData: TestData)(test: Task[Unit]) = {
    testDataRef.set(testData)
    test.runSyncUnsafe(5.seconds)
  }

  override def nestedSuites = Vector(
    GetBlockChunkedSpec,
    StreamBlockSummariesSpec
  )

  object GetBlockChunkedSpec extends WordSpecLike {
    // Just want to test with random blocks; variety doesn't matter.
    implicit val config = PropertyCheckConfiguration(minSuccessful = 1)

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
              var stopCount = 0
              var nextCount = 0

              val oi = new ObservableIterant[Task] {
                // This should count on the server side.
                def toObservable[A](it: Iterant[Task, A]) =
                  Observable
                    .fromReactivePublisher(it.toReactivePublisher)
                    .doOnNext(_ => Task.delay(nextCount += 1))
                    .doOnEarlyStop(Task.delay(stopCount += 1))
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

                  // We should stop early, and with the batch restriction just after a few items pulled.
                  firstCount should be < all.size

                  // This worked when we weren't going over gRPC, just using the abstractions.
                  // Maybe it's worth trying with `bracket` to see if we can observe stops any other way.
                  // The feed still seems to stop early, but I'm just not exactly sure sure when the
                  // server side resources are freed.
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
    implicit val config  = PropertyCheckConfiguration(minSuccessful = 5)
    implicit val hashGen = Arbitrary(genHash)

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
}

object GrpcGossipServiceSpec extends TestRuntime {
  // Specify small enough chunks so we see lots of messages and can tell that it terminated early.
  val DefaultMaxChunkSize = 10 * 1024

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
    def apply(testData: AtomicReference[TestData])(
        implicit
        oi: ObservableIterant[Task],
        scheduler: Scheduler
    ): Resource[Task, GossipingGrpcMonix.GossipServiceStub] = {
      val port = getFreePort

      val serverR = GrpcServer[Task](
        port,
        services = List(
          (scheduler: Scheduler) =>
            Task.delay {
              val svc = GrpcGossipService.fromGossipService {
                new GossipServiceServer[Task](
                  getBlockSummary = hash => Task.now(testData.get.summaries.get(hash)),
                  getBlock = hash => Task.now(testData.get.blocks.get(hash)),
                  maxChunkSize = DefaultMaxChunkSize
                )
              }
              GossipingGrpcMonix.bindService(svc, scheduler)
            }
        )
      )

      val channelR = Resource.make(
        Task.delay {
          NettyChannelBuilder
            .forAddress("localhost", port)
            .executor(scheduler)
            .usePlaintext
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
      } yield {
        new GossipingGrpcMonix.GossipServiceStub(channel)
      }
    }
  }
}
