package io.casperlabs.node.configuration
import java.io.File
import java.nio.file.{Path, Paths}

import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import org.scalacheck.{Arbitrary, Gen}

import scala.concurrent.duration._

trait ArbitraryImplicits {
  implicit val pathGen: Arbitrary[Path] = Arbitrary {
    for {
      n     <- Gen.choose(1, 10)
      paths <- Gen.listOfN(n, Gen.listOfN(3, Gen.alphaNumChar)).map(_.flatten.mkString(""))
    } yield Paths.get("/", paths.mkString(File.pathSeparator))
  }

  //Needed to pass through CLI options parsing
  implicit val nonEmptyStringGen: Arbitrary[String] = Arbitrary {
    for {
      n   <- Gen.choose(1, 100)
      seq <- Gen.listOfN(n, Gen.alphaNumChar)
    } yield seq.mkString("")
  }

  //There is no way expressing explicit 'false' using CLI options.
  implicit val optionBooleanGen: Arbitrary[Boolean] = Arbitrary {
    Gen.const(true)
  }

  implicit val peerNodeGen: Arbitrary[PeerNode] = Arbitrary {
    for {
      n        <- Gen.choose(1, 100)
      bytes    <- Gen.listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue))
      id       = NodeIdentifier(bytes)
      host     <- Gen.listOfN(n, Gen.alphaNumChar)
      tcpPort  <- Gen.posNum[Int]
      udpPort  <- Gen.posNum[Int]
      endpoint = Endpoint(host.mkString(""), tcpPort, udpPort)
    } yield PeerNode(id, endpoint)
  }

  // There are some comparison problems with default generator
  implicit val finiteDurationGen: Arbitrary[FiniteDuration] = Arbitrary {
    for {
      n <- Gen.choose(0, Int.MaxValue)
    } yield FiniteDuration(n.toLong, MILLISECONDS)
  }
}
