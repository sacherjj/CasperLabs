package io.casperlabs.smartcontracts.cltype

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

  implicit val fromBytesCLType: FromBytes[CLType] = new FromBytes[CLType] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (CLType, BytesView)] =
      FromBytes.safePop(bytes).flatMap {
        case (tag, tail) if tag == CL_TYPE_TAG_BOOL   => Right(Bool   -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_I32    => Right(I32    -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_I64    => Right(I64    -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_U8     => Right(U8     -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_U32    => Right(U32    -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_U64    => Right(U64    -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_U128   => Right(U128   -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_U256   => Right(U256   -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_U512   => Right(U512   -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_UNIT   => Right(Unit   -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_STRING => Right(String -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_KEY    => Right(Key    -> tail)
        case (tag, tail) if tag == CL_TYPE_TAG_UREF   => Right(URef   -> tail)

        case (tag, tail) if tag == CL_TYPE_TAG_OPTION =>
          FromBytes[CLType].fromBytes(tail).map { case (inner, rem) => Option(inner) -> rem }

        case (tag, tail) if tag == CL_TYPE_TAG_LIST =>
          FromBytes[CLType].fromBytes(tail).map { case (inner, rem) => List(inner) -> rem }

        case (tag, tail) if tag == CL_TYPE_TAG_FIXED_LIST =>
          for {
            (inner, rem1) <- FromBytes[CLType].fromBytes(tail)
            (n, rem2)     <- FromBytes[Int].fromBytes(rem1)
          } yield FixedList(inner, n) -> rem2

        case (tag, tail) if tag == CL_TYPE_TAG_RESULT =>
          for {
            (ok, rem1)  <- FromBytes[CLType].fromBytes(tail)
            (err, rem2) <- FromBytes[CLType].fromBytes(rem1)
          } yield Result(ok, err) -> rem2

        case (tag, tail) if tag == CL_TYPE_TAG_MAP =>
          for {
            (key, rem1)   <- FromBytes[CLType].fromBytes(tail)
            (value, rem2) <- FromBytes[CLType].fromBytes(rem1)
          } yield Map(key, value) -> rem2

        case (tag, tail) if tag == CL_TYPE_TAG_TUPLE1 =>
          FromBytes[CLType].fromBytes(tail).map { case (inner, rem) => Tuple1(inner) -> rem }

        case (tag, tail) if tag == CL_TYPE_TAG_TUPLE2 =>
          for {
            (t1, rem1) <- FromBytes[CLType].fromBytes(tail)
            (t2, rem2) <- FromBytes[CLType].fromBytes(rem1)
          } yield Tuple2(t1, t2) -> rem2

        case (tag, tail) if tag == CL_TYPE_TAG_TUPLE3 =>
          for {
            (t1, rem1) <- FromBytes[CLType].fromBytes(tail)
            (t2, rem2) <- FromBytes[CLType].fromBytes(rem1)
            (t3, rem3) <- FromBytes[CLType].fromBytes(rem2)
          } yield Tuple3(t1, t2, t3) -> rem3

        case (tag, tail) if tag == CL_TYPE_TAG_ANY => Right(Any -> tail)

        case (other, _) => Left(FromBytes.Error.InvalidVariantTag(other, "CLType"))
      }
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
