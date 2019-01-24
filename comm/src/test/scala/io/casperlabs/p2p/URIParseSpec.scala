package io.casperlabs.p2p

import org.scalatest._
import io.casperlabs.comm._

class URIParseSpec extends FlatSpec with Matchers {
  def badAddressError(s: String): Either[ParseError, PeerNode] =
    Left(ParseError(s"bad address: $s"))

  "A well formed casperlabs URI" should "parse into a PeerNode" in {
    val uri = "casperlabs://abcdef@localhost?protocol=12345&discovery=12346"
    PeerNode.fromAddress(uri) should be(
      Right(
        PeerNode(
          NodeIdentifier(Seq(0xAB.toByte, 0xCD.toByte, 0xEF.toByte)),
          Endpoint("localhost", 12345, 12346)
        )
      )
    )
  }

  "A non-casperlabs URI" should "parse as an error" in {
    val uri = "http://foo.bar.baz/quux"
    PeerNode.fromAddress(uri) should be(badAddressError(uri))
  }

  "A URI without protocol" should "parse as an error" in {
    val uri = "abcde@localhost?protocol=12345&discovery=12346"
    PeerNode.fromAddress(uri) should be(badAddressError(uri))
  }

  "An casperlabs URI with non-integral port" should "parse as an error" in {
    val uri = "casperlabs://abcde@localhost:smtp"
    PeerNode.fromAddress(uri) should be(badAddressError(uri))
  }

  "An casperlabs URI without a key" should "parse as an error" in {
    val uri = "casperlabs://localhost?protocol=12345&discovery=12346"
    PeerNode.fromAddress(uri) should be(badAddressError(uri))
  }
}
