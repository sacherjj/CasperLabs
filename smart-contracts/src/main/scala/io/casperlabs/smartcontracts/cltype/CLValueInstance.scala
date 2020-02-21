package io.casperlabs.smartcontracts.cltype

import cats.free.Free
import cats.implicits._
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.smartcontracts.bytesrepr.{BytesView, Constants, FromBytes}
import io.casperlabs.smartcontracts.cltype
import scala.collection.immutable

sealed trait CLValueInstance {
  val clType: CLType
}

object CLValueInstance {
  def from(v: CLValue): Either[FromBytes.Error, CLValueInstance] =
    FromBytes.deserialize(deserializer(v.clType), v.value.toArray)

  def deserializer(t: CLType): FromBytes.Deserializer[CLValueInstance] = t match {
    case CLType.Bool => FromBytes.bool.map(b => Bool(b))

    case CLType.I32 => FromBytes.int.map(i => I32(i))

    case CLType.I64 => FromBytes.long.map(i => I64(i))

    case CLType.U8 => FromBytes.byte.map(b => U8(b))

    case CLType.U32 => FromBytes.int.map(i => U32(i))

    case CLType.U64 => FromBytes.long.map(i => U64(i))

    case CLType.U128 =>
      bigIntNN.map(nn => U128(nn))

    case CLType.U256 =>
      bigIntNN.map(nn => U256(nn))

    case CLType.U512 =>
      bigIntNN.map(nn => U512(nn))

    case CLType.Unit => FromBytes.unit.map(_ => Unit)

    case CLType.String => FromBytes.string.map(s => String(s))

    case CLType.Key => cltype.Key.deserializer.map(k => Key(k))

    case CLType.URef => cltype.URef.deserializer.map(u => URef(u))

    case CLType.Option(inner) =>
      FromBytes
        .option(deserializer(inner))
        .flatMap(instance => lift(Option(instance, inner)))

    case CLType.List(inner) =>
      FromBytes
        .seq(deserializer(inner))
        .flatMap(instance => lift(List(instance, inner)))

    case CLType.FixedList(inner, n) =>
      FromBytes
        .fixedSeq(deserializer(inner), n)
        .flatMap(instances => lift(FixedList(instances, inner, n)))

    case CLType.Result(ok, err) =>
      FromBytes
        .either(deserializer(err), deserializer(ok))
        .flatMap(instance => lift(Result(instance, ok, err)))

    case CLType.Map(key, value) =>
      FromBytes
        .map(deserializer(key), deserializer(value))
        .flatMap(instance => lift(Map(instance, key, value)))

    case CLType.Tuple1(t1) =>
      FromBytes
        .tuple1(deserializer(t1))
        .map { case scala.Tuple1(_1) => Tuple1(_1) }

    case CLType.Tuple2(t1, t2) =>
      FromBytes
        .tuple2(
          deserializer(t1),
          deserializer(t2)
        )
        .map {
          case (_1, _2) => Tuple2(_1, _2)
        }

    case CLType.Tuple3(t1, t2, t3) =>
      FromBytes
        .tuple3(
          deserializer(t1),
          deserializer(t2),
          deserializer(t3)
        )
        .map {
          case (_1, _2, _3) => Tuple3(_1, _2, _3)
        }

    case CLType.Any =>
      FromBytes.raise(FromBytes.Error.FormatException("Cannot instantiate CLType.Any"))
  }

  case class Bool(value: Boolean) extends CLValueInstance {
    override val clType: CLType = CLType.Bool
  }

  case class I32(value: Int) extends CLValueInstance {
    override val clType: CLType = CLType.I32
  }

  case class I64(value: Long) extends CLValueInstance {
    override val clType: CLType = CLType.I64
  }

  case class U8(value: Byte) extends CLValueInstance {
    override val clType: CLType = CLType.U8
  }

  case class U32(value: Int) extends CLValueInstance {
    override val clType: CLType = CLType.U32
  }

  case class U64(value: Long) extends CLValueInstance {
    override val clType: CLType = CLType.U64
  }

  case class U128(value: BigInt Refined NonNegative) extends CLValueInstance {
    override val clType: CLType = CLType.U128
  }

  case class U256(value: BigInt Refined NonNegative) extends CLValueInstance {
    override val clType: CLType = CLType.U256
  }

  case class U512(value: BigInt Refined NonNegative) extends CLValueInstance {
    override val clType: CLType = CLType.U512
  }

  case object Unit extends CLValueInstance { override val clType: CLType = CLType.Unit }

  case class String(value: java.lang.String) extends CLValueInstance {
    override val clType: CLType = CLType.String
  }

  case class Key(value: cltype.Key) extends CLValueInstance {
    override val clType: CLType = CLType.Key
  }

  case class URef(value: cltype.URef) extends CLValueInstance {
    override val clType: CLType = CLType.URef
  }

  case class Option private (value: scala.Option[CLValueInstance], inner: CLType)
      extends CLValueInstance {
    override val clType: CLType = CLType.Option(inner)
  }
  object Option {
    def apply(
        value: scala.Option[CLValueInstance],
        innerType: CLType
    ): Either[Error.TypeMismatch, Option] =
      value match {
        case None => Right(new Option(value, innerType))
        case Some(innerValue) =>
          Error.TypeMismatch.detect(innerValue, innerType).map(_ => new Option(value, innerType))
      }
  }

  case class List private (value: Seq[CLValueInstance], inner: CLType) extends CLValueInstance {
    override val clType: CLType = CLType.List(inner)
  }
  object List {
    def apply(value: Seq[CLValueInstance], innerType: CLType): Either[Error.TypeMismatch, List] =
      value.find(_.clType != innerType) match {
        case None => Right(new List(value, innerType))
        case Some(badValue) =>
          Left(Error.TypeMismatch(valueType = badValue.clType, targetType = innerType))
      }
  }

  case class FixedList private (value: Seq[CLValueInstance], inner: CLType, length: Int)
      extends CLValueInstance {
    override val clType: CLType = CLType.FixedList(inner, length)
  }
  object FixedList {
    def apply(
        value: Seq[CLValueInstance],
        innerType: CLType,
        length: Int
    ): Either[Error, FixedList] =
      value.find(_.clType != innerType) match {
        case None =>
          val n = value.size
          if (n != length) Left(Error.InvalidLength(valueLength = n, typeLength = length))
          else Right(new FixedList(value, innerType, length))

        case Some(badValue) =>
          Left(Error.TypeMismatch(valueType = badValue.clType, targetType = innerType))
      }
  }

  case class Result private (
      value: Either[CLValueInstance, CLValueInstance],
      ok: CLType,
      err: CLType
  ) extends CLValueInstance {
    override val clType: CLType = CLType.Result(ok, err)
  }
  object Result {
    def apply(
        value: Either[CLValueInstance, CLValueInstance],
        ok: CLType,
        err: CLType
    ): Either[Error.TypeMismatch, Result] = value match {
      case Left(left)   => Error.TypeMismatch.detect(left, err).map(_ => new Result(value, ok, err))
      case Right(right) => Error.TypeMismatch.detect(right, ok).map(_ => new Result(value, ok, err))
    }
  }

  case class Map(
      value: immutable.Map[CLValueInstance, CLValueInstance],
      keyType: CLType,
      valueType: CLType
  ) extends CLValueInstance {
    override val clType: CLType = CLType.Map(keyType, valueType)
  }
  object Map {
    def apply(
        value: immutable.Map[CLValueInstance, CLValueInstance],
        k: CLType,
        v: CLType
    ): Either[Error.TypeMismatch, Map] =
      value.keys.find(_.clType != k) match {
        case Some(badKey) => Left(Error.TypeMismatch(valueType = badKey.clType, targetType = k))
        case None =>
          value.values.find(_.clType != v) match {
            case Some(badValue) =>
              Left(Error.TypeMismatch(valueType = badValue.clType, targetType = v))
            case None => Right(new Map(value, k, v))
          }
      }
  }

  case class Tuple1(_1: CLValueInstance) extends CLValueInstance {
    override val clType: CLType = CLType.Tuple1(_1.clType)
  }

  case class Tuple2(_1: CLValueInstance, _2: CLValueInstance) extends CLValueInstance {
    override val clType: CLType = CLType.Tuple2(_1.clType, _2.clType)
  }

  case class Tuple3(_1: CLValueInstance, _2: CLValueInstance, _3: CLValueInstance)
      extends CLValueInstance {
    override val clType: CLType = CLType.Tuple3(_1.clType, _2.clType, _3.clType)
  }

  sealed trait Error
  object Error {
    case class TypeMismatch(valueType: CLType, targetType: CLType) extends Error
    object TypeMismatch {
      def detect(instance: CLValueInstance, target: CLType): Either[TypeMismatch, Unit] =
        if (instance.clType == target) Right(())
        else Left(TypeMismatch(valueType = instance.clType, targetType = target))
    }

    case class InvalidLength(valueLength: Int, typeLength: Int) extends Error
  }

  private def lift[T <: CLValueInstance, E <: Error](
      maybeInstance: Either[E, T]
  ): FromBytes.Deserializer[
    CLValueInstance
  ] =
    maybeInstance match {
      case Left(err) =>
        FromBytes.raise(FromBytes.Error.FormatException(s"CLValueInstance.Error: $err"))

      case Right(result) => FromBytes.pure(result)
    }

  private val bigIntNN: FromBytes.Deserializer[BigInt Refined NonNegative] =
    FromBytes.bigInt.flatMap { i =>
      refineV[NonNegative](i) match {
        case Left(_) => FromBytes.raise(FromBytes.Error.FormatException("Negative BigInt"))

        case Right(nn) => FromBytes.pure(nn)
      }
    }
}
