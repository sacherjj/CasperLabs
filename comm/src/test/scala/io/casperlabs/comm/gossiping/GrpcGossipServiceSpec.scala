package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.Block
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import monix.eval.Task
import monix.execution.Scheduler
import scala.concurrent.duration._

class GrpcGossipServiceSpec
    extends WordSpecLike
    with Matchers
    with GeneratorDrivenPropertyChecks
    with ArbitraryConsensus {

  import GrpcGossipServiceSpec._
  import Scheduler.Implicits.global

  implicit override val generatorDrivenConfig =
    PropertyCheckConfiguration(
      minSuccessful = 3
    )

  def runTest(test: Task[Unit]) =
    test.runSyncUnsafe(5.seconds)

  "getBlocksChunked" when {
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
              chunks.head.getHeader.compressionAlgorithm shouldBe ""
              chunks.size should be > 1

              Inspectors.forAll(chunks.tail) { chunk =>
                chunk.content.isData shouldBe true
                chunk.getData.size should be <= DefaultMaxChunkSize
              }

              val data = chunks.tail.flatMap(_.getData.toByteArray).toArray
              data shouldBe block.toByteArray
              chunks.head.getHeader.contentLength shouldBe data.size
            }
          }
        }
      }
    }

    "gzip compression is supported" should {
      "return a stream of compressed chunks" in {
        pending
      }
    }

    "chunk size is specified" when {
      "it is less then the maximum" should {
        "use the requested chunk size" in {
          pending
        }
      }

      "bigger than the maximum" should {
        "use the default chunk size" in {
          pending
        }
      }
    }

    "iteration is abandoned" should {
      "cancel the source" in {
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
