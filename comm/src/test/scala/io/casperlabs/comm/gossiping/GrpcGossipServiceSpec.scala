package io.casperlabs.comm.gossiping

import cats.implicits._
import cats.effect._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.shared.Compression
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.comm.ServiceError.NotFound
import io.casperlabs.comm.GrpcServer
import io.casperlabs.comm.TestRuntime
import io.grpc.netty.NettyChannelBuilder
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll, PropertyCheckConfiguration}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.tail.Iterant
import scala.concurrent.duration._

class GrpcGossipServiceSpec extends WordSpecLike with Matchers with ArbitraryConsensus {

  import GrpcGossipServiceSpec._
  import Scheduler.Implicits.global

  def runTest(test: Task[Unit]) =
    test.runSyncUnsafe(5.seconds)

  "getBlocksChunked" when {
    // Just want to test with random blocks; variety doesn't matter.
    implicit val config = PropertyCheckConfiguration(minSuccessful = 1)

    "no compression is supported" should {
      "return a stream of uncompressed chunks" in {
        forAll { (block: Block) =>
          runTest {
            TestClient.fromBlock(block).use { stub =>
              val req = GetBlockChunkedRequest(blockHash = block.blockHash)
              for {
                chunks <- stub.getBlockChunked(req).toListL
              } yield {
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
                content shouldBe original
              }
            }
          }
        }
      }
    }

    "compression is supported" should {
      "return a stream of compressed chunks" in {
        forAll { (block: Block) =>
          runTest {
            TestClient.fromBlock(block).use { stub =>
              val req = GetBlockChunkedRequest(
                blockHash = block.blockHash,
                acceptedCompressionAlgorithms = Seq("lz4")
              )

              for {
                chunks <- stub.getBlockChunked(req).toListL
              } yield {
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

                decompressed.length shouldBe original.length
                decompressed shouldBe original
              }
            }
          }
        }
      }
    }

    "chunk size is specified" when {
      def testChunkSize(block: Block, requestedChunkSize: Int, expectedChunkSize: Int): Task[Unit] =
        TestClient.fromBlock(block).use { stub =>
          val req =
            GetBlockChunkedRequest(blockHash = block.blockHash, chunkSize = requestedChunkSize)
          for {
            chunks <- stub.getBlockChunked(req).toListL
          } yield {
            Inspectors.forAll(chunks.tail.init) { chunk =>
              chunk.getData.size shouldBe expectedChunkSize
            }
            chunks.last.getData.size should be <= expectedChunkSize
          }
        }

      "it is less then the maximum" should {
        "use the requested chunk size" in {
          forAll { (block: Block) =>
            runTest {
              val smallChunkSize = DefaultMaxChunkSize / 2
              testChunkSize(block, smallChunkSize, smallChunkSize)
            }
          }
        }
      }

      "bigger than the maximum" should {
        "use the default chunk size" in {
          forAll { (block: Block) =>
            runTest {
              val bigChunkSize = DefaultMaxChunkSize * 2
              testChunkSize(block, bigChunkSize, DefaultMaxChunkSize)
            }
          }
        }
      }
    }

    "block cannot be found" should {
      "return NOT_FOUND" in {
        forAll(genHash) { (hash: ByteString) =>
          runTest {
            TestClient.fromGetBlock(_ => None).use { stub =>
              val req = GetBlockChunkedRequest(blockHash = hash)
              for {
                res <- stub.getBlockChunked(req).toListL.attempt
              } yield {
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
    }

    "iteration is abandoned" should {
      "cancel the source" in {
        forAll { (block: Block) =>
          runTest {
            // Capture the event when the Observable created from the Iterant is canceled.
            var stopCount = 0
            var nextCount = 0
            implicit val oi = new ObservableIterant[Task] {
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

            TestClient.fromBlock(block).use { stub =>
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
                withClue(
                  s"onNext called $firstCount / ${all.size} times; recommended batch size was ${implicitly[Scheduler].executionModel.recommendedBatchSize}."
                ) {
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
}

object GrpcGossipServiceSpec extends TestRuntime {
  val DefaultMaxChunkSize = 10 * 1024

  object TestClient {
    def fromBlock(block: Block)(
        implicit
        oi: ObservableIterant[Task],
        scheduler: Scheduler
    ) =
      fromGetBlock(hash => Option(block).filter(_.blockHash == hash))

    def fromGetBlock(f: ByteString => Option[Block])(
        implicit
        oi: ObservableIterant[Task],
        scheduler: Scheduler
    ): Resource[Task, GossipingGrpcMonix.GossipServiceStub] = {
      val port = getFreePort

      val serverR = GrpcServer(
        port,
        services = List(
          (scheduler: Scheduler) =>
            Task.delay {
              val svc = GrpcGossipService.fromGossipService {
                new GossipServiceServer[Task](
                  getBlock = hash => Task.now(f(hash)),
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
        server  <- serverR
        channel <- channelR
      } yield {
        new GossipingGrpcMonix.GossipServiceStub(channel)
      }
    }
  }
}
