package io.casperlabs.client.configuration

import org.scalatest._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Deploy.Arg
import io.casperlabs.casper.consensus.state.BigInt
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
      {"name": "purse_id", "value": {"optional_value": {}}},
      {"name": "purse_id", "value": {"optional_value": {"long_value": ${amount}}}},
      {"name": "surname", "value": {"string_value": "Nakamoto"}},
      {"name": "number", "value": {"big_int": {"value": "2", "bit_width": 512}}}
    ]
    """

    Args.fromJson(json) match {
      case Left(error) =>
        fail(error)
      case Right(args) =>
        args should have size 6
        args(0) shouldBe Arg("amount").withValue(Arg.Value(Arg.Value.Value.LongValue(amount)))
        args(1) shouldBe Arg("account").withValue(
          Arg.Value(Arg.Value.Value.BytesValue(ByteString.copyFrom(account)))
        )
        args(2) shouldBe Arg("purse_id").withValue(
          Arg.Value(Arg.Value.Value.OptionalValue(Arg.Value(Arg.Value.Value.Empty)))
        )
        args(3) shouldBe Arg("purse_id").withValue(
          Arg.Value(Arg.Value.Value.OptionalValue(Arg.Value(Arg.Value.Value.LongValue(amount))))
        )
        args(4) shouldBe Arg("surname").withValue(
          Arg.Value(Arg.Value.Value.StringValue("Nakamoto"))
        )
        args(5) shouldBe Arg("number").withValue(
          Arg.Value(Arg.Value.Value.BigInt(BigInt("2", 512)))
        )
    }
  }
}
