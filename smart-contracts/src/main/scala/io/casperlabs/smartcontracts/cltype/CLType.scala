package io.casperlabs.smartcontracts.cltype

import cats.data.NonEmptyList
import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}
import scala.annotation.tailrec
import scala.collection.immutable

sealed trait CLType

object CLType {
  case object Bool                                      extends CLType
  case object I32                                       extends CLType
  case object I64                                       extends CLType
  case object U8                                        extends CLType
  case object U32                                       extends CLType
  case object U64                                       extends CLType
  case object U128                                      extends CLType
  case object U256                                      extends CLType
  case object U512                                      extends CLType
  case object Unit                                      extends CLType
  case object String                                    extends CLType
  case object Key                                       extends CLType
  case object URef                                      extends CLType
  case class Option(t: CLType)                          extends CLType
  case class List(t: CLType)                            extends CLType
  case class FixedList(t: CLType, length: Int)          extends CLType
  case class Result(ok: CLType, err: CLType)            extends CLType
  case class Map(key: CLType, value: CLType)            extends CLType
  case class Tuple1(t: CLType)                          extends CLType
  case class Tuple2(t1: CLType, t2: CLType)             extends CLType
  case class Tuple3(t1: CLType, t2: CLType, t3: CLType) extends CLType
  case object Any                                       extends CLType

  private type LoopState = immutable.List[Either[Int, CLType]]

  @tailrec
  private def toBytesTailRec(state: LoopState, acc: IndexedSeq[Byte]): Array[Byte] = state match {
    case Nil => acc.toArray

    case Left(n) :: tail => toBytesTailRec(tail, acc ++ ToBytes[Int].toBytes(n))

    case Right(t) :: tail =>
      t match {
        case Bool   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_BOOL)
        case I32    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_I32)
        case I64    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_I64)
        case U8     => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U8)
        case U32    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U32)
        case U64    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U64)
        case U128   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U128)
        case U256   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U256)
        case U512   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_U512)
        case Unit   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_UNIT)
        case String => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_STRING)
        case Key    => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_KEY)
        case URef   => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_UREF)

        case Option(inner) => toBytesTailRec(Right(inner) :: tail, acc :+ CL_TYPE_TAG_OPTION)

        case List(inner) => toBytesTailRec(Right(inner) :: tail, acc :+ CL_TYPE_TAG_LIST)

        case FixedList(inner, n) =>
          toBytesTailRec(Right(inner) :: Left(n) :: tail, acc :+ CL_TYPE_TAG_FIXED_LIST)

        case Result(ok, err) =>
          toBytesTailRec(Right(ok) :: Right(err) :: tail, acc :+ CL_TYPE_TAG_RESULT)

        case Map(key, value) =>
          toBytesTailRec(Right(key) :: Right(value) :: tail, acc :+ CL_TYPE_TAG_MAP)

        case Tuple1(inner) => toBytesTailRec(Right(inner) :: tail, acc :+ CL_TYPE_TAG_TUPLE1)

        case Tuple2(t1, t2) =>
          toBytesTailRec(Right(t1) :: Right(t2) :: tail, acc :+ CL_TYPE_TAG_TUPLE2)

        case Tuple3(t1, t2, t3) =>
          toBytesTailRec(Right(t1) :: Right(t2) :: Right(t3) :: tail, acc :+ CL_TYPE_TAG_TUPLE3)

        case Any => toBytesTailRec(tail, acc :+ CL_TYPE_TAG_ANY)
      }
  }

  implicit val toBytesCLType: ToBytes[CLType] = new ToBytes[CLType] {
    override def toBytes(t: CLType): Array[Byte] = toBytesTailRec(Right(t) :: Nil, IndexedSeq.empty)
  }

  @tailrec
  def fromBytesTailRec(
      bytes: BytesView,
      builders: NonEmptyList[CLTypeBuilder]
  ): Either[FromBytes.Error, (CLType, BytesView)] =
    FromBytes.safePop(bytes) match {
      case Left(err) => Left(err)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_BOOL =>
        CLTypeBuilder.buildFrom(Bool, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_I32 =>
        CLTypeBuilder.buildFrom(I32, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_I64 =>
        CLTypeBuilder.buildFrom(I64, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_U8 =>
        CLTypeBuilder.buildFrom(U8, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_U32 =>
        CLTypeBuilder.buildFrom(U32, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_U64 =>
        CLTypeBuilder.buildFrom(U64, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_U128 =>
        CLTypeBuilder.buildFrom(U128, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_U256 =>
        CLTypeBuilder.buildFrom(U256, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_U512 =>
        CLTypeBuilder.buildFrom(U512, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_UNIT =>
        CLTypeBuilder.buildFrom(Unit, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_STRING =>
        CLTypeBuilder.buildFrom(String, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_KEY =>
        CLTypeBuilder.buildFrom(Key, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_UREF =>
        CLTypeBuilder.buildFrom(URef, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((tag, tail)) if tag == CL_TYPE_TAG_OPTION =>
        fromBytesTailRec(tail, CLTypeBuilder.OptionHole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_LIST =>
        fromBytesTailRec(tail, CLTypeBuilder.ListHole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_FIXED_LIST =>
        fromBytesTailRec(tail, CLTypeBuilder.FixedListHole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_RESULT =>
        fromBytesTailRec(tail, CLTypeBuilder.ResultHole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_MAP =>
        fromBytesTailRec(tail, CLTypeBuilder.MapHole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_TUPLE1 =>
        fromBytesTailRec(tail, CLTypeBuilder.Tuple1Hole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_TUPLE2 =>
        fromBytesTailRec(tail, CLTypeBuilder.Tuple2Hole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_TUPLE3 =>
        fromBytesTailRec(tail, CLTypeBuilder.Tuple3Hole :: builders)

      case Right((tag, tail)) if tag == CL_TYPE_TAG_ANY =>
        CLTypeBuilder.buildFrom(Any, builders, tail) match {
          case Right((Right(clType), remBytes))     => Right(clType -> remBytes)
          case Right((Left(remBuilders), remBytes)) => fromBytesTailRec(remBytes, remBuilders)
          case Left(err)                            => Left(err)
        }

      case Right((other, _)) => Left(FromBytes.Error.InvalidVariantTag(other, "CLType"))
    }

  implicit val fromBytesCLType: FromBytes[CLType] = new FromBytes[CLType] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (CLType, BytesView)] =
      fromBytesTailRec(bytes, NonEmptyList.one(CLTypeBuilder.Hole))
  }

  val CL_TYPE_TAG_BOOL: Byte       = 0
  val CL_TYPE_TAG_I32: Byte        = 1
  val CL_TYPE_TAG_I64: Byte        = 2
  val CL_TYPE_TAG_U8: Byte         = 3
  val CL_TYPE_TAG_U32: Byte        = 4
  val CL_TYPE_TAG_U64: Byte        = 5
  val CL_TYPE_TAG_U128: Byte       = 6
  val CL_TYPE_TAG_U256: Byte       = 7
  val CL_TYPE_TAG_U512: Byte       = 8
  val CL_TYPE_TAG_UNIT: Byte       = 9
  val CL_TYPE_TAG_STRING: Byte     = 10
  val CL_TYPE_TAG_KEY: Byte        = 11
  val CL_TYPE_TAG_UREF: Byte       = 12
  val CL_TYPE_TAG_OPTION: Byte     = 13
  val CL_TYPE_TAG_LIST: Byte       = 14
  val CL_TYPE_TAG_FIXED_LIST: Byte = 15
  val CL_TYPE_TAG_RESULT: Byte     = 16
  val CL_TYPE_TAG_MAP: Byte        = 17
  val CL_TYPE_TAG_TUPLE1: Byte     = 18
  val CL_TYPE_TAG_TUPLE2: Byte     = 19
  val CL_TYPE_TAG_TUPLE3: Byte     = 20
  val CL_TYPE_TAG_ANY: Byte        = 21
}
