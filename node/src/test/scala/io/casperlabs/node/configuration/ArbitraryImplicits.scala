package io.casperlabs.node.configuration
import java.io.File
import java.nio.file.{Path, Paths}

import com.google.protobuf.ByteString
import io.casperlabs.comm.discovery.Node
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
  implicit val booleanGen: Arbitrary[Boolean] = Arbitrary {
    Gen.const(true)
  }

  implicit val nodeGen: Arbitrary[Node] = Arbitrary {
    for {
      n       <- Gen.choose(1, 100)
      bytes   <- Gen.listOfN(n, Gen.choose(Byte.MinValue, Byte.MaxValue))
      id      = ByteString.copyFrom(bytes.toArray)
      host    <- Gen.listOfN(n, Gen.alphaNumChar)
      tcpPort <- Gen.posNum[Int]
      udpPort <- Gen.posNum[Int]
    } yield Node(id, host.mkString(""), tcpPort, udpPort)
  }

  // There are some comparison problems with default generator
  implicit val finiteDurationGen: Arbitrary[FiniteDuration] = Arbitrary {
    for {
      n <- Gen.choose(0, Int.MaxValue)
    } yield FiniteDuration(n.toLong, MILLISECONDS)
  }
}
