package io.casperlabs.client.configuration

import org.scalatest._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Deploy.Arg
import io.casperlabs.casper.consensus.state.BigInt
import io.casperlabs.casper.consensus.state.Key
import io.casperlabs.casper.consensus.state.IntList
import io.casperlabs.casper.consensus.state.StringList
import com.google.protobuf.ByteString

class ArgsSpec extends FlatSpec with Matchers {
  behavior of "Args"

  it should "parse JSON arguments" in {
    val int_value   = 12345
    val amount      = 123456L
    val account     = Array.range(0, 32).map(_.toByte)
    val hex_account = Base16.encode(account)
    val json        = s"""
    [
      {"name": "my_long_value", "value": {"long_value": ${amount}}},
      {"name": "my_account", "value": {"bytes_value": "${hex_account}"}},
      {"name": "maybe_long", "value": {"optional_value": {}}},
      {"name": "maybe_long", "value": {"optional_value": {"long_value": ${amount}}}},
      {"name": "surname", "value": {"string_value": "Nakamoto"}},
      {"name": "big_number", "value": {"big_int": {"value": "2", "bit_width": 512}}},
      {"name": "my_hash", "value": {"key": {"hash": {"hash": "${hex_account}"}}}},
      {"name": "my_address", "value": {"key": {"address": {"account": "${hex_account}"}}}},
      {"name": "my_uref", "value": {"key": {"uref": {"uref": "${hex_account}", "access_rights": 5}}}},
      {"name": "my_local", "value": {"key": {"local": {"hash": "${hex_account}"}}}},
      {"name": "my_int_value", "value": {"int_value": ${int_value}}},
      {"name": "my_int_list", "value": {"int_list": {"values": [0, 1, 2]}}},
      {"name": "my_string_list", "value": {"string_list": {"values": ["A", "B", "C"]}}}
    ]
    """

    Args.fromJson(json) match {
      case Left(error) =>
        fail(error)
      case Right(args) =>
        args should have size 13
        args(0) shouldBe Arg("my_long_value").withValue(
          Arg.Value(Arg.Value.Value.LongValue(amount))
        )
        args(1) shouldBe Arg("my_account").withValue(
          Arg.Value(Arg.Value.Value.BytesValue(ByteString.copyFrom(account)))
        )
        args(2) shouldBe Arg("maybe_long").withValue(
          Arg.Value(Arg.Value.Value.OptionalValue(Arg.Value(Arg.Value.Value.Empty)))
        )
        args(3) shouldBe Arg("maybe_long").withValue(
          Arg.Value(Arg.Value.Value.OptionalValue(Arg.Value(Arg.Value.Value.LongValue(amount))))
        )
        args(4) shouldBe Arg("surname").withValue(
          Arg.Value(Arg.Value.Value.StringValue("Nakamoto"))
        )
        args(5) shouldBe Arg("big_number").withValue(
          Arg.Value(Arg.Value.Value.BigInt(BigInt("2", 512)))
        )
        args(6) shouldBe Arg("my_hash").withValue(
          Arg.Value(Arg.Value.Value.Key(Key().withHash(Key.Hash(ByteString.copyFrom(account)))))
        )
        args(7) shouldBe Arg("my_address").withValue(
          Arg.Value(
            Arg.Value.Value.Key(Key().withAddress(Key.Address(ByteString.copyFrom(account))))
          )
        )
        args(8) shouldBe Arg("my_uref").withValue(
          Arg.Value(
            Arg.Value.Value.Key(
              Key().withUref(Key.URef(ByteString.copyFrom(account), Key.URef.AccessRights.READ_ADD))
            )
          )
        )
        args(9) shouldBe Arg("my_local").withValue(
          Arg.Value(Arg.Value.Value.Key(Key().withLocal(Key.Local(ByteString.copyFrom(account)))))
        )
        args(10) shouldBe Arg("my_int_value").withValue(
          Arg.Value(Arg.Value.Value.IntValue(int_value))
        )
        args(11) shouldBe Arg("my_int_list").withValue(
          Arg.Value(Arg.Value.Value.IntList(IntList(Array(0, 1, 2))))
        )
        args(12) shouldBe Arg("my_string_list").withValue(
          Arg.Value(Arg.Value.Value.StringList(StringList(Array("A", "B", "C"))))
        )
    }
  }
}
