package io.casperlabs.comm.discovery

import io.casperlabs.comm.gossiping.ArbitraryConsensus
import org.scalacheck.Shrink
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpecLike}

class NodeDiscoverySpec
    extends WordSpecLike
    with GeneratorDrivenPropertyChecks
    with Matchers
    with ArbitraryConsensus {
  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  "KademliaNodeDiscovery" when {
    "lookup" should {
      "converge to the closest node" in pending
    }
    "discover" should {
      "skip itself during lookup" in pending
      "stop iterative lookup when successfully called 'k' peers" in pending
      "stop iterative lookup when no closer node returned" in pending
      "fill the peer table with successfully responded peers" in pending
      "perform at most 'alpha' concurrent requests" in pending
    }
  }
}
