package io.casperlabs.models.cltype

import io.casperlabs.casper.consensus.state

object ProtoConstructor {
  def bool(b: Boolean): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.BoolValue(b)
  )

  def i32(i: Int): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.I32(i)
  )

  def i64(i: Long): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.I64(i)
  )

  def u8(b: Byte): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.U8(b.toInt)
  )

  def u32(i: Int): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.U32(i)
  )

  def u64(i: Long): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.U64(i)
  )

  def u128(i: BigInt): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.U128(
      state.CLValueInstance.U128(i.toString)
    )
  )

  def u256(i: BigInt): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.U256(
      state.CLValueInstance.U256(i.toString)
    )
  )

  def u512(i: BigInt): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.U512(
      state.CLValueInstance.U512(i.toString)
    )
  )

  val unit: state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.Unit(state.Unit())
  )

  def string(s: String): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.StrValue(s)
  )

  def key(k: state.Key): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.Key(k)
  )

  def uref(u: state.Key.URef): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.Uref(u)
  )

  def option(inner: Option[state.CLValueInstance.Value]): state.CLValueInstance.Value =
    state.CLValueInstance.Value(
      value = state.CLValueInstance.Value.Value.OptionValue(
        state.CLValueInstance.OptionProto(inner)
      )
    )

  def list(inner: Seq[state.CLValueInstance.Value]): state.CLValueInstance.Value =
    state.CLValueInstance.Value(
      value = state.CLValueInstance.Value.Value.ListValue(
        state.CLValueInstance.List(inner)
      )
    )

  def fixedList(inner: Seq[state.CLValueInstance.Value]): state.CLValueInstance.Value =
    state.CLValueInstance.Value(
      value = state.CLValueInstance.Value.Value.FixedListValue(
        state.CLValueInstance.FixedList(inner.size, inner)
      )
    )

  def result(
      inner: Either[state.CLValueInstance.Value, state.CLValueInstance.Value]
  ): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.ResultValue(
      state.CLValueInstance.Result(
        inner.fold(
          state.CLValueInstance.Result.Value.Err.apply,
          state.CLValueInstance.Result.Value.Ok.apply
        )
      )
    )
  )

  def map(inner: Seq[state.CLValueInstance.MapEntry]): state.CLValueInstance.Value =
    state.CLValueInstance.Value(
      value = state.CLValueInstance.Value.Value.MapValue(
        state.CLValueInstance.Map(inner)
      )
    )

  def tuple1(_1: state.CLValueInstance.Value): state.CLValueInstance.Value =
    state.CLValueInstance.Value(
      value = state.CLValueInstance.Value.Value.Tuple1Value(
        state.CLValueInstance.Tuple1(Some(_1))
      )
    )

  def tuple2(
      _1: state.CLValueInstance.Value,
      _2: state.CLValueInstance.Value
  ): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.Tuple2Value(
      state.CLValueInstance.Tuple2(Some(_1), Some(_2))
    )
  )

  def tuple3(
      _1: state.CLValueInstance.Value,
      _2: state.CLValueInstance.Value,
      _3: state.CLValueInstance.Value
  ): state.CLValueInstance.Value = state.CLValueInstance.Value(
    value = state.CLValueInstance.Value.Value.Tuple3Value(
      state.CLValueInstance.Tuple3(Some(_1), Some(_2), Some(_3))
    )
  )
}
