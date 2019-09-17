package io.casperlabs.client.configuration

import org.scalatest._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Deploy.Arg
import io.casperlabs.casper.consensus.state.BigInt
import io.casperlabs.casper.consensus.state.Key
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
      {"name": "maybe_long", "value": {"optional_value": {}}},
      {"name": "maybe_long", "value": {"optional_value": {"long_value": ${amount}}}},
      {"name": "surname", "value": {"string_value": "Nakamoto"}},
      {"name": "number", "value": {"big_int": {"value": "2", "bit_width": 512}}},
      {"name": "my_hash", "value": {"key": {"hash": {"hash": "${Base16.encode(account)}"}}}},
      {"name": "my_address", "value": {"key": {"address": {"account": "${Base16.encode(account)}"}}}},
      {"name": "my_uref", "value": {"key": {"uref": {"uref": "${Base16.encode(account)}", "access_rights": 5}}}},
      {"name": "my_local", "value": {"key": {"local": {"hash": "${Base16.encode(account)}"}}}}
    ]
    """

    Args.fromJson(json) match {
      case Left(error) =>
        fail(error)
      case Right(args) =>
        args should have size 10
        args(0) shouldBe Arg("amount").withValue(Arg.Value(Arg.Value.Value.LongValue(amount)))
        args(1) shouldBe Arg("account").withValue(
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
        args(5) shouldBe Arg("number").withValue(
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
    }
  }
}
