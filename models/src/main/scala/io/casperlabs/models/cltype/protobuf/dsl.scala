package io.casperlabs.models.cltype.protobuf

import cats.syntax.option._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.{CLType, CLValueInstance, Key, Unit}

object dsl {

  object types {
    val bool: CLType   = CLType(CLType.Variants.SimpleType(CLType.Simple.BOOL))
    val i32: CLType    = CLType(CLType.Variants.SimpleType(CLType.Simple.I32))
    val i64: CLType    = CLType(CLType.Variants.SimpleType(CLType.Simple.I64))
    val u8: CLType     = CLType(CLType.Variants.SimpleType(CLType.Simple.U8))
    val u32: CLType    = CLType(CLType.Variants.SimpleType(CLType.Simple.U32))
    val u64: CLType    = CLType(CLType.Variants.SimpleType(CLType.Simple.U64))
    val u128: CLType   = CLType(CLType.Variants.SimpleType(CLType.Simple.U128))
    val u256: CLType   = CLType(CLType.Variants.SimpleType(CLType.Simple.U256))
    val u512: CLType   = CLType(CLType.Variants.SimpleType(CLType.Simple.U512))
    val unit: CLType   = CLType(CLType.Variants.SimpleType(CLType.Simple.UNIT))
    val string: CLType = CLType(CLType.Variants.SimpleType(CLType.Simple.STRING))
    val key: CLType    = CLType(CLType.Variants.SimpleType(CLType.Simple.KEY))
    val uref: CLType   = CLType(CLType.Variants.SimpleType(CLType.Simple.UREF))

    def option(inner: CLType): CLType =
      CLType(CLType.Variants.OptionType(CLType.OptionProto(inner.some)))

    def list(inner: CLType): CLType =
      CLType(CLType.Variants.ListType(CLType.List(inner.some)))

    def fixedList(inner: CLType, length: Int): CLType =
      CLType(
        CLType.Variants.FixedListType(CLType.FixedList(inner.some, length))
      )

    def result(ok: CLType, err: CLType): CLType =
      CLType(
        CLType.Variants
          .ResultType(CLType.Result(ok = ok.some, err = err.some))
      )

    def map(key: CLType, value: CLType): CLType =
      CLType(
        CLType.Variants
          .MapType(CLType.Map(key = key.some, value = value.some))
      )

    def tuple1(inner: CLType): CLType =
      CLType(CLType.Variants.Tuple1Type(CLType.Tuple1(inner.some)))

    def tuple2(t1: CLType, t2: CLType): CLType =
      CLType(
        CLType.Variants
          .Tuple2Type(CLType.Tuple2(t1.some, t2.some))
      )

    def tuple3(t1: CLType, t2: CLType, t3: CLType): CLType =
      CLType(
        CLType.Variants
          .Tuple3Type(CLType.Tuple3(t1.some, t2.some, t3.some))
      )

    val any: CLType = CLType(CLType.Variants.AnyType(CLType.Any()))
  }

  object values {
    def bool(b: Boolean): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.BoolValue(b)
    )

    def i32(i: Int): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.I32(i)
    )

    def i64(i: Long): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.I64(i)
    )

    def u8(b: Byte): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.U8(b.toInt)
    )

    def u32(i: Int): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.U32(i)
    )

    def u64(i: Long): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.U64(i)
    )

    def u128(i: BigInt): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.U128(
        CLValueInstance.U128(i.toString)
      )
    )

    def u256(i: BigInt): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.U256(
        CLValueInstance.U256(i.toString)
      )
    )

    def u512(i: BigInt): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.U512(
        CLValueInstance.U512(i.toString)
      )
    )

    val unit: CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.Unit(Unit())
    )

    def string(s: String): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.StrValue(s)
    )

    def key(k: Key): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.Key(k)
    )

    def uref(u: Key.URef): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.Uref(u)
    )

    def bytes(bs: Seq[Byte]): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.BytesValue(ByteString.copyFrom(bs.toArray))
    )

    def option(inner: Option[CLValueInstance.Value]): CLValueInstance.Value =
      CLValueInstance.Value(
        value = CLValueInstance.Value.Value.OptionValue(
          CLValueInstance.OptionProto(inner)
        )
      )

    def list(inner: Seq[CLValueInstance.Value]): CLValueInstance.Value =
      CLValueInstance.Value(
        value = CLValueInstance.Value.Value.ListValue(
          CLValueInstance.List(inner)
        )
      )

    def fixedList(inner: Seq[CLValueInstance.Value]): CLValueInstance.Value =
      CLValueInstance.Value(
        value = CLValueInstance.Value.Value.FixedListValue(
          CLValueInstance.FixedList(inner.size, inner)
        )
      )

    def result(
        inner: Either[CLValueInstance.Value, CLValueInstance.Value]
    ): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.ResultValue(
        CLValueInstance.Result(
          inner.fold(
            CLValueInstance.Result.Value.Err.apply,
            CLValueInstance.Result.Value.Ok.apply
          )
        )
      )
    )

    def map(inner: Seq[CLValueInstance.MapEntry]): CLValueInstance.Value =
      CLValueInstance.Value(
        value = CLValueInstance.Value.Value.MapValue(
          CLValueInstance.Map(inner)
        )
      )

    def tuple1(_1: CLValueInstance.Value): CLValueInstance.Value =
      CLValueInstance.Value(
        value = CLValueInstance.Value.Value.Tuple1Value(
          CLValueInstance.Tuple1(Some(_1))
        )
      )

    def tuple2(
        _1: CLValueInstance.Value,
        _2: CLValueInstance.Value
    ): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.Tuple2Value(
        CLValueInstance.Tuple2(Some(_1), Some(_2))
      )
    )

    def tuple3(
        _1: CLValueInstance.Value,
        _2: CLValueInstance.Value,
        _3: CLValueInstance.Value
    ): CLValueInstance.Value = CLValueInstance.Value(
      value = CLValueInstance.Value.Value.Tuple3Value(
        CLValueInstance.Tuple3(Some(_1), Some(_2), Some(_3))
      )
    )
  }

  object instances {
    def bool(b: Boolean): CLValueInstance = CLValueInstance(
      clType = types.bool.some,
      value = values.bool(b).some
    )

    def i32(i: Int): CLValueInstance = CLValueInstance(
      clType = types.i32.some,
      value = values.i32(i).some
    )

    def i64(i: Long): CLValueInstance = CLValueInstance(
      clType = types.i64.some,
      value = values.i64(i).some
    )

    def u8(b: Byte): CLValueInstance = CLValueInstance(
      clType = types.u8.some,
      value = values.u8(b).some
    )

    def u32(i: Int): CLValueInstance = CLValueInstance(
      clType = types.u32.some,
      value = values.u32(i).some
    )

    def u64(i: Long): CLValueInstance = CLValueInstance(
      clType = types.u64.some,
      value = values.u64(i).some
    )

    def u128(i: BigInt): CLValueInstance = CLValueInstance(
      clType = types.u128.some,
      value = values.u128(i).some
    )

    def u256(i: BigInt): CLValueInstance = CLValueInstance(
      clType = types.u256.some,
      value = values.u256(i).some
    )

    def u512(i: BigInt): CLValueInstance = CLValueInstance(
      clType = types.u512.some,
      value = values.u512(i).some
    )

    val unit: CLValueInstance = CLValueInstance(
      clType = types.unit.some,
      value = values.unit.some
    )

    def string(s: String): CLValueInstance = CLValueInstance(
      clType = types.string.some,
      value = values.string(s).some
    )

    def key(k: Key): CLValueInstance = CLValueInstance(
      clType = types.key.some,
      value = values.key(k).some
    )

    def uref(u: Key.URef): CLValueInstance = CLValueInstance(
      clType = types.uref.some,
      value = values.uref(u).some
    )

    def bytes(bs: Seq[Byte]): CLValueInstance = CLValueInstance(
      clType = types.list(types.u8).some,
      value = values.bytes(bs).some
    )

    def bytesFixedLength(bs: Seq[Byte]): CLValueInstance = CLValueInstance(
      clType = types.fixedList(types.u8, bs.size).some,
      value = values.bytes(bs).some
    )

    object option {
      def some(element: CLValueInstance): CLValueInstance = CLValueInstance(
        clType = element.clType.map(types.option),
        value = values.option(element.value).some
      )

      def none(innerType: CLType): CLValueInstance = CLValueInstance(
        clType = types.option(innerType).some,
        value = values.option(None).some
      )
    }

    object list {
      def empty(innerType: CLType): CLValueInstance = CLValueInstance(
        clType = types.list(innerType).some,
        value = values.list(Nil).some
      )

      def apply(elements: Seq[CLValueInstance]): CLValueInstance = CLValueInstance(
        clType = elements.headOption.flatMap(_.clType).fold(types.any)(types.list).some,
        value = values.list(elements.flatMap(_.value)).some
      )
    }

    object fixedList {
      def empty(innerType: CLType): CLValueInstance = CLValueInstance(
        clType = types.fixedList(innerType, 0).some,
        value = values.fixedList(Nil).some
      )

      def apply(elements: Seq[CLValueInstance]): CLValueInstance = CLValueInstance(
        clType = elements.headOption
          .flatMap(_.clType)
          .fold(types.any)(types.fixedList(_, elements.size))
          .some,
        value = values.fixedList(elements.flatMap(_.value)).some
      )
    }

    object result {
      def ok(element: CLValueInstance, errType: CLType): CLValueInstance = CLValueInstance(
        clType = types.result(element.clType.getOrElse(types.any), errType).some,
        value = element.value.map(v => values.result(Right(v)))
      )

      def err(element: CLValueInstance, okType: CLType): CLValueInstance = CLValueInstance(
        clType = types.result(okType, element.clType.getOrElse(types.any)).some,
        value = element.value.map(v => values.result(Left(v)))
      )
    }

    object map {
      def empty(keyType: CLType, valueType: CLType): CLValueInstance = CLValueInstance(
        clType = types.map(keyType, valueType).some,
        value = values.map(Nil).some
      )

      def apply(elements: Seq[(CLValueInstance, CLValueInstance)]): CLValueInstance =
        CLValueInstance(
          clType = elements.headOption
            .fold(types.map(types.any, types.any)) {
              case (key, value) =>
                types.map(key.clType.getOrElse(types.any), value.clType.getOrElse(types.any))
            }
            .some,
          value = values
            .map(
              elements.map {
                case (key, value) => CLValueInstance.MapEntry(key.value, value.value)
              }
            )
            .some
        )
    }

    def tuple1(_1: CLValueInstance): CLValueInstance =
      CLValueInstance(
        clType = _1.clType.map(types.tuple1),
        value = _1.value.map(values.tuple1)
      )

    def tuple2(
        _1: CLValueInstance,
        _2: CLValueInstance
    ): CLValueInstance = CLValueInstance(
      clType = for {
        t1 <- _1.clType
        t2 <- _2.clType
      } yield types.tuple2(t1, t2),
      value = for {
        v1 <- _1.value
        v2 <- _2.value
      } yield values.tuple2(v1, v2)
    )

    def tuple3(
        _1: CLValueInstance,
        _2: CLValueInstance,
        _3: CLValueInstance
    ): CLValueInstance = CLValueInstance(
      clType = for {
        t1 <- _1.clType
        t2 <- _2.clType
        t3 <- _3.clType
      } yield types.tuple3(t1, t2, t3),
      value = for {
        v1 <- _1.value
        v2 <- _2.value
        v3 <- _3.value
      } yield values.tuple3(v1, v2, v3)
    )
  }

}
