package io.casperlabs.client.configuration

import org.scalatest._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Deploy.Arg
import com.google.protobuf.ByteString

class ArgsSpec extends FlatSpec with Matchers {
  behavior of "Args"

  it should "parse JSON arguments" in {
    val amount  = 123456L
    val account = Array.range(0, 32).map(_.toByte)
    val json    = s"""
    [
      {"name": "amount", "value": {"long_value": ${amount}}},
      {"name": "account", "value": {"bytes_value": "${Base16.encode(account)}"}},
      {"name": "purse_id", "value": {"optional_value": {}}}
    ]
    """

    Args.fromJson(json) match {
      case Left(error) =>
        fail(error)
      case Right(args) =>
        args should have size 3
        args(0) shouldBe Arg("amount").withValue(Arg.Value(Arg.Value.Value.LongValue(amount)))
        args(1) shouldBe Arg("account").withValue(
          Arg.Value(Arg.Value.Value.BytesValue(ByteString.copyFrom(account)))
        )
        args(2) shouldBe Arg("purse_id").withValue(
          Arg.Value(Arg.Value.Value.OptionalValue(Arg.Value(Arg.Value.Value.Empty)))
        )
    }
  }
}
