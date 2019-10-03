package io.casperlabs.comm.gossiping

import io.casperlabs.comm.discovery.Node
import io.casperlabs.models.ArbitraryConsensus
import org.scalacheck.{Arbitrary, Gen}

trait ArbitraryConsensusAndComm extends ArbitraryConsensus {
  implicit val arbNode: Arbitrary[Node] = Arbitrary {
    for {
      id   <- genHash
      host <- Gen.listOfN(4, Gen.choose(0, 255)).map(xs => xs.mkString("."))
    } yield {
      Node(id, host, 40400, 40404)
    }
  }
}
