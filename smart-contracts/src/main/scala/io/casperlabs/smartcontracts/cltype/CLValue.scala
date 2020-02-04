package io.casperlabs.smartcontracts.cltype

import cats.implicits._
import io.casperlabs.smartcontracts.bytesrepr
import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}

case class CLValue(clType: CLType, value: IndexedSeq[Byte]) {
  def to[T: CLTyped: FromBytes]: Either[CLValue.Error, T] =
    if (clType != CLTyped[T].clType)
      Left(CLValue.Error.TypeMismatch(valueType = clType, targetType = CLTyped[T].clType))
    else FromBytes.deserialize[T](value.toArray).leftMap(err => CLValue.Error.FromBytes(err))
}

object CLValue {
  def from[T: CLTyped: ToBytes](t: T): CLValue = CLValue(
    CLTyped[T].clType,
    ToBytes.toBytes(t)
  )

  def option(inner: Option[CLValue]): CLValue = inner match {
    // TODO: the type here could be a problem; if we get a `None` we know
    // that we should return a `CLType.Option`, but we do not know what
    // the inner type should be. I am choosing `Any` for now, but
    // maybe we will need to revisit this decision in the future.
    case None => CLValue(CLType.Option(CLType.Any), Vector(bytesrepr.Constants.Option.NONE_TAG))

    case Some(value) =>
      CLValue(CLType.Option(value.clType), bytesrepr.Constants.Option.SOME_TAG +: value.value)
  }

  implicit val toBytesCLValue: ToBytes[CLValue] = new ToBytes[CLValue] {
    override def toBytes(v: CLValue): Array[Byte] =
      ToBytes.toBytes(v.value) ++ ToBytes.toBytes(v.clType)
  }

  implicit val fromBytesCLValue: FromBytes[CLValue] = new FromBytes[CLValue] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (CLValue, BytesView)] =
      for {
        (value, tail) <- FromBytes[Seq[Byte]].fromBytes(bytes)
        (clType, rem) <- FromBytes[CLType].fromBytes(tail)
      } yield CLValue(clType, value.toIndexedSeq) -> rem
  }

  sealed trait Error
  object Error {
    case class TypeMismatch(valueType: CLType, targetType: CLType) extends Error
    case class FromBytes(error: bytesrepr.FromBytes.Error)         extends Error
  }
}
