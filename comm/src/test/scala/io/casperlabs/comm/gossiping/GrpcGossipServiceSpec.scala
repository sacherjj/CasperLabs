package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.Block
import io.casperlabs.shared.Compression
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll, PropertyCheckConfiguration}
import monix.eval.Task
import monix.execution.Scheduler
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
            for {
              svc    <- TestService.fromBlock(block)
              req    = GetBlockChunkedRequest(blockHash = block.blockHash)
              chunks <- svc.getBlockChunked(req).toListL
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

    "compression is supported" should {
      "return a stream of compressed chunks" in {
        forAll { (block: Block) =>
          runTest {
            for {
              svc <- TestService.fromBlock(block)
              req = GetBlockChunkedRequest(
                blockHash = block.blockHash,
                acceptedCompressionAlgorithms = Seq("lz4")
              )
              chunks <- svc.getBlockChunked(req).toListL
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

              decompressed.size shouldBe original.size
              decompressed shouldBe original
            }
          }
        }
      }
    }

    "chunk size is specified" when {
      def testChunkSize(block: Block, requestedChunkSize: Int, expectedChunkSize: Int): Task[Unit] =
        for {
          svc    <- TestService.fromBlock(block)
          req    = GetBlockChunkedRequest(blockHash = block.blockHash, chunkSize = requestedChunkSize)
          chunks <- svc.getBlockChunked(req).toListL
        } yield {
          Inspectors.forAll(chunks.tail.init) { chunk =>
            chunk.getData.size shouldBe expectedChunkSize
          }
          chunks.last.getData.size should be <= expectedChunkSize
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

    "iteration is abandoned" should {
      "cancel the source" in {
        pending
      }
    }

    "block cannot be found" should {
      "return NOT_FOUND" in {
        pending
      }
    }
  }
}

object GrpcGossipServiceSpec extends Matchers {
  val DefaultMaxChunkSize = 100 * 1024

  object TestService {
    def fromBlock(block: Block)(implicit scheduler: Scheduler) =
      GrpcGossipService.fromGossipService[Task] {
        new GossipServiceImpl[Task](
          getBlock = hash => {
            hash shouldBe block.blockHash
            Task.now(block)
          },
          maxChunkSize = DefaultMaxChunkSize
        )
      }
  }
}
