package io.casperlabs.casper.api

import cats.Id
import com.google.protobuf.{ByteString, Int64Value}
import io.casperlabs.casper.protocol.DeployData
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b512Random
import io.casperlabs.p2p.EffectsTestInstances.LogStub

import org.scalatest.{FlatSpec, Matchers}

class PreviewPrivateNameTest extends FlatSpec with Matchers {
  implicit val logEff = new LogStub[Id]

  def previewId(pkHex: String, timestamp: Long, nth: Int = 0): String = {
    val preview = BlockAPI.previewPrivateNames[Id](
      ProtoUtil.stringToByteString(pkHex),
      timestamp,
      nth + 1
    )

    Base16.encode(preview.ids(nth).toByteArray)
  }

  val myNodePk = "464f6780d71b724525be14348b59c53dc8795346dfd7576c9f01c397ee7523e6"

  "previewPrivateNames" should "work in one case" in {
    // When we deploy `new x ...` code from a javascript gRPC client,
    // we get this private name id in the log:
    // 16:41:08.995 [node-runner-15] INFO  c.r.casper.MultiParentCasperImpl - Received Deploy #1542308065454 -- new x0, x1 in {
    //   @{x1}!(...
    // [Unforgeable(0xb5630d1bfb836635126ee7f2770873937933679e38146b1ddfbfcc14d7d8a787), bundle+ {   Unforgeable(0x00) }]
    // 2018-11-15T18:54:25.454Z
    previewId(myNodePk, 1542308065454L) should equal(
      "6f592abcfd696351044ebceace3d06d4fb4c6504382ef290fb017cb8fd5d7c10"
    )
  }

  "previewPrivateNames" should "work for another timestamp" in {
    previewId(myNodePk, 1542315551822L) should equal(
      "7f8b81b50c17f1e21c5689983cc3f4d214bac76ba04f7e9a6651da42fa895806"
    )
  }

  "previewPrivateNames" should "handle empty user (public key)" in {
    previewId("", 1542308065454L) should equal(
      "cbf1d22609f426cfcf6acd17893940cc07dbeabf35748db4e34bbd7d9e5d859e"
    )
  }

  "previewPrivateNames" should "work for more than one name" in {
    previewId(myNodePk, 1542308065454L, 1) should equal(
      "ff72d22d741d42b97159c3cca222d5c96d7b77a43090a1e339cb2a8b242cfc4b"
    )
  }
}
