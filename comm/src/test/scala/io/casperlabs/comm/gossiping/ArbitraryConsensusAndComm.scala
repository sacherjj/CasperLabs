package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.comm.discovery.Node
import io.casperlabs.models.ArbitraryConsensus
import org.scalacheck.{Arbitrary, Gen}

trait ArbitraryConsensusAndComm extends ArbitraryConsensus {

  implicit def arbNode(implicit chainId: ByteString): Arbitrary[Node] = Arbitrary {
    for {
      id   <- genHash
      host <- Gen.listOfN(4, Gen.choose(0, 255)).map(xs => xs.mkString("."))
    } yield {
      Node(id, host, 40400, 40404, chainId)
    }
  }
}
