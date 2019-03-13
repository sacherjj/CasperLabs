package io.casperlabs.comm.gossiping

import org.scalatest._

class GrpcGossipServiceSpec extends WordSpecLike with Matchers {

  "getBlocksChunked" when {
    "no compression is supported" should {
      "return a stream of uncompressed chunks" in {
        pending
      }
    }

    "gzip compression is supported" should {
      "return a stream of compressed chunks" in {
        pending
      }
    }
  }
}
