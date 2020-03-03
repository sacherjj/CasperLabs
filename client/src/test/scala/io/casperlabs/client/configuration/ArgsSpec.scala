package io.casperlabs.client.configuration

import cats.syntax.option._
import cats.syntax.either._
import org.scalatest._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.Deploy.Arg
import io.casperlabs.casper.consensus.state
import io.casperlabs.models.cltype.CLType
import io.casperlabs.models.cltype.protobuf.{Constructor, Mappings}
import com.google.protobuf.ByteString

class ArgsSpec extends FlatSpec with Matchers {
  behavior of "Args"

  it should "parse JSON arguments" in {
    val bool       = true
    val int        = 314
    val long       = 2342L
    val byte: Byte = 7
    val bigInt     = BigInt("123456789101112131415161718")
    val string     = "Hello, world!"
    val address    = Array.range(0, 32).map(_.toByte)
    val addressHex = Base16.encode(address)

    val json = s"""
    [
      {"name" : "bool", "value" : {"cl_type" : { "simple_type" : "BOOL" }, "value" : { "bool_value" : $bool }}},

      {"name" : "i32", "value" : {"cl_type" : { "simple_type" : "I32" }, "value" : { "i32" : $int }}},

      {"name" : "i64", "value" : {"cl_type" : { "simple_type" : "I64" }, "value" : { "i64" : $long }}},

      {"name" : "u8", "value" : {"cl_type" : { "simple_type" : "U8" }, "value" : { "u8" : $byte }}},

      {"name" : "u32", "value" : {"cl_type" : { "simple_type" : "U32" }, "value" : { "u32" : $int }}},

      {"name" : "u64", "value" : {"cl_type" : { "simple_type" : "U64" }, "value" : { "u64" : $long }}},

      {"name" : "u128", "value" : {"cl_type" : { "simple_type" : "U128" }, "value" : { "u128" :  {"value" : "${bigInt.toString}"}}}},

      {"name" : "u256", "value" : {"cl_type" : { "simple_type" : "U256" }, "value" : { "u256" :  {"value" : "${bigInt.toString}"}}}},

      {"name" : "u512", "value" : {"cl_type" : { "simple_type" : "U512" }, "value" : { "u512" :  {"value" : "${bigInt.toString}"}}}},

      {"name" : "unit", "value" : {"cl_type" : { "simple_type" : "UNIT" }, "value" : { "unit" : {} }}},

      {"name" : "string", "value" : {"cl_type" : { "simple_type" : "STRING" }, "value" : { "str_value" : "$string" }}},

      {"name" : "accountKey", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"address": {"account": "$addressHex"}}}}},

      {"name" : "hashKey", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"hash": {"hash": "$addressHex"}}}}},

      {"name" : "localKey", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"local": {"hash": "$addressHex"}}}}},

      {"name" : "urefKey", "value" : {"cl_type" : { "simple_type" : "KEY" }, "value" : {"key": {"uref": {"uref": "$addressHex", "access_rights": 5}}}}},

      {"name" : "uref", "value" : {"cl_type" : { "simple_type" : "UREF" }, "value" : {"uref": {"uref": "$addressHex", "access_rights": 5}}}},

      {
        "name" : "maybe_u64",
        "value" : {
          "cl_type" : {"option_type" : {"inner" : {"simple_type" : "U64"}}},
          "value" : {"option_value" : {}}
        }
      },

      {
        "name" : "maybe_u64",
        "value" : {
          "cl_type" : {"option_type" : {"inner" : {"simple_type" : "U64"}}},
          "value" : {"option_value" : {"value" : {"u64" : $long}}}
        }
      },

      {
        "name" : "list_i32",
        "value" : {
          "cl_type" : {"list_type" : {"inner" : {"simple_type" : "I32"}}},
          "value" : {"list_value" : {"values" : [{"i32" : 0}, {"i32" : 1}, {"i32" : 2}, {"i32" : 3}]}}
        }
      },

      {
        "name" : "fixed_list_str",
        "value" : {
          "cl_type" : {"fixed_list_type" : {"inner" : {"simple_type" : "STRING"}, "len" : 3}},
          "value" : {"fixed_list_value" : {"length" : 3, "values" : [{"str_value" : "A"}, {"str_value" : "B"}, {"str_value" : "C"}]}}
        }
      },

      {
        "name" : "err_string",
        "value" : {
          "cl_type" : {"result_type" : {"ok" : {"simple_type" : "BOOL"}, "err" : {"simple_type" : "STRING"}}},
          "value" : {"result_value" : {"err" : {"str_value" : "$string"}}}
        }
      },

      {
        "name" : "ok_bool",
        "value" : {
          "cl_type" : {"result_type" : {"ok" : {"simple_type" : "BOOL"}, "err" : {"simple_type" : "STRING"}}},
          "value" : {"result_value" : {"ok" : {"bool_value" : $bool}}}
        }
      },

      {
        "name" : "map_string_i32",
        "value" : {
          "cl_type" : {"map_type" : {"key" : {"simple_type" : "STRING"}, "value" : {"simple_type" : "I32"}}},
          "value" : {
            "map_value" : {
              "values" : [
                {"key" : {"str_value" : "A"}, "value" : {"i32" : 0}},
                {"key" : {"str_value" : "B"}, "value" : {"i32" : 1}},
                {"key" : {"str_value" : "C"}, "value" : {"i32" : 2}}
              ]
            }
          }
        }
      },

      {
        "name" : "tuple1",
        "value" : {
          "cl_type" : {"tuple1_type" : {"type0" : {"simple_type" : "U8"}}},
          "value" : {"tuple1_value" : {"value_1" : {"u8" : $byte}}}
        }
      },

      {
        "name" : "tuple2",
        "value" : {
          "cl_type" : {"tuple2_type" : {"type0" : {"simple_type" : "U8"}, "type1" : {"simple_type" : "U32"}}},
          "value" : {"tuple2_value" : {"value_1" : {"u8" : $byte}, "value_2" : {"u32" : $int}}}
        }
      },

      {
        "name" : "tuple3",
        "value" : {
          "cl_type" : {"tuple3_type" : {
            "type0" : {"simple_type" : "U8"},
            "type1" : {"simple_type" : "U32"},
            "type2" : {"simple_type" : "U64"}
          }},
          "value" : {"tuple3_value" : {
            "value_1" : {"u8" : $byte},
            "value_2" : {"u32" : $int},
            "value_3" : {"u64" : $long}
          }}
        }
      }
    ]
    """

    val args = Args.fromJson(json).fold(e => fail(e), identity)

    args should have size 26
    args(0) shouldBe Arg("bool").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Bool).some,
        value = Constructor.bool(bool).some
      )
    )
    args(1) shouldBe Arg("i32").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.I32).some,
        value = Constructor.i32(int).some
      )
    )
    args(2) shouldBe Arg("i64").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.I64).some,
        value = Constructor.i64(long).some
      )
    )
    args(3) shouldBe Arg("u8").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.U8).some,
        value = Constructor.u8(byte).some
      )
    )
    args(4) shouldBe Arg("u32").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.U32).some,
        value = Constructor.u32(int).some
      )
    )
    args(5) shouldBe Arg("u64").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.U64).some,
        value = Constructor.u64(long).some
      )
    )
    args(6) shouldBe Arg("u128").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.U128).some,
        value = Constructor.u128(bigInt).some
      )
    )
    args(7) shouldBe Arg("u256").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.U256).some,
        value = Constructor.u256(bigInt).some
      )
    )
    args(8) shouldBe Arg("u512").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.U512).some,
        value = Constructor.u512(bigInt).some
      )
    )
    args(9) shouldBe Arg("unit").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Unit).some,
        value = Constructor.unit.some
      )
    )
    args(10) shouldBe Arg("string").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.String).some,
        value = Constructor.string(string).some
      )
    )
    args(11) shouldBe Arg("accountKey").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Key).some,
        value = Constructor
          .key(state.Key().withAddress(state.Key.Address(ByteString.copyFrom(address))))
          .some
      )
    )
    args(12) shouldBe Arg("hashKey").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Key).some,
        value = Constructor
          .key(state.Key().withHash(state.Key.Hash(ByteString.copyFrom(address))))
          .some
      )
    )
    args(13) shouldBe Arg("localKey").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Key).some,
        value = Constructor
          .key(state.Key().withLocal(state.Key.Local(ByteString.copyFrom(address))))
          .some
      )
    )
    args(14) shouldBe Arg("urefKey").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Key).some,
        value = Constructor
          .key(
            state
              .Key()
              .withUref(
                state.Key
                  .URef(ByteString.copyFrom(address), state.Key.URef.AccessRights.READ_ADD)
              )
          )
          .some
      )
    )
    args(15) shouldBe Arg("uref").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.URef).some,
        value = Constructor
          .uref(
            state.Key
              .URef(ByteString.copyFrom(address), state.Key.URef.AccessRights.READ_ADD)
          )
          .some
      )
    )
    args(16) shouldBe Arg("maybe_u64").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Option(CLType.U64)).some,
        value = Constructor.option(None).some
      )
    )
    args(17) shouldBe Arg("maybe_u64").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Option(CLType.U64)).some,
        value = Constructor.option(Some(Constructor.u64(long))).some
      )
    )
    args(18) shouldBe Arg("list_i32").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.List(CLType.I32)).some,
        value = Constructor.list(List(0, 1, 2, 3).map(Constructor.i32)).some
      )
    )
    args(19) shouldBe Arg("fixed_list_str").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.FixedList(CLType.String, 3)).some,
        value = Constructor.fixedList(List("A", "B", "C").map(Constructor.string)).some
      )
    )
    args(20) shouldBe Arg("err_string").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Result(CLType.Bool, CLType.String)).some,
        value = Constructor.result(Left(string).leftMap(Constructor.string)).some
      )
    )
    args(21) shouldBe Arg("ok_bool").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Result(CLType.Bool, CLType.String)).some,
        value = Constructor.result(Right(bool).map(Constructor.bool)).some
      )
    )
    args(22) shouldBe Arg("map_string_i32").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Map(CLType.String, CLType.I32)).some,
        value = Constructor
          .map(
            List("A" -> 0, "B" -> 1, "C" -> 2).map {
              case (key, value) =>
                state.CLValueInstance
                  .MapEntry(Constructor.string(key).some, Constructor.i32(value).some)
            }
          )
          .some
      )
    )
    args(23) shouldBe Arg("tuple1").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Tuple1(CLType.U8)).some,
        value = Constructor.tuple1(Constructor.u8(byte)).some
      )
    )
    args(24) shouldBe Arg("tuple2").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Tuple2(CLType.U8, CLType.U32)).some,
        value = Constructor.tuple2(Constructor.u8(byte), Constructor.u32(int)).some
      )
    )
    args(25) shouldBe Arg("tuple3").withValue(
      state.CLValueInstance(
        clType = Mappings.toProto(CLType.Tuple3(CLType.U8, CLType.U32, CLType.U64)).some,
        value = Constructor
          .tuple3(Constructor.u8(byte), Constructor.u32(int), Constructor.u64(long))
          .some
      )
    )
  }

  // TODO: remove this when there are no more usages of the old format
  it should "parse JSON legacy arguments" in {
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
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.I64).some,
            value = Constructor.i64(amount).some
          )
        )
        args(1) shouldBe Arg("my_account").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.FixedList(CLType.U8, 32)).some,
            value = Constructor.fixedList(account.map(Constructor.u8)).some
          )
        )
        args(2) shouldBe Arg("maybe_long").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.Option(CLType.Any)).some,
            value = Constructor.option(None).some
          )
        )
        args(3) shouldBe Arg("maybe_long").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.Option(CLType.I64)).some,
            value = Constructor.option(Some(Constructor.i64(amount))).some
          )
        )
        args(4) shouldBe Arg("surname").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.String).some,
            value = Constructor.string("Nakamoto").some
          )
        )
        args(5) shouldBe Arg("big_number").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.U512).some,
            value = Constructor.u512(2).some
          )
        )
        args(6) shouldBe Arg("my_hash").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.Key).some,
            value = Constructor
              .key(state.Key().withHash(state.Key.Hash(ByteString.copyFrom(account))))
              .some
          )
        )
        args(7) shouldBe Arg("my_address").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.Key).some,
            value = Constructor
              .key(state.Key().withAddress(state.Key.Address(ByteString.copyFrom(account))))
              .some
          )
        )
        args(8) shouldBe Arg("my_uref").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.Key).some,
            value = Constructor
              .key(
                state
                  .Key()
                  .withUref(
                    state.Key
                      .URef(ByteString.copyFrom(account), state.Key.URef.AccessRights.READ_ADD)
                  )
              )
              .some
          )
        )
        args(9) shouldBe Arg("my_local").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.Key).some,
            value = Constructor
              .key(state.Key().withLocal(state.Key.Local(ByteString.copyFrom(account))))
              .some
          )
        )
        args(10) shouldBe Arg("my_int_value").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.I32).some,
            value = Constructor.i32(int_value).some
          )
        )
        args(11) shouldBe Arg("my_int_list").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.List(CLType.I32)).some,
            value = Constructor.list(List(0, 1, 2).map(Constructor.i32)).some
          )
        )
        args(12) shouldBe Arg("my_string_list").withValue(
          state.CLValueInstance(
            clType = Mappings.toProto(CLType.List(CLType.String)).some,
            value = Constructor.list(List("A", "B", "C").map(Constructor.string)).some
          )
        )
    }
  }
}
