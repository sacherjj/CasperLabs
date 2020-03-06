package io.casperlabs.models.cltype

import cats.implicits._
import io.casperlabs.models.bytesrepr
import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

case class CLValue(clType: CLType, value: IndexedSeq[Byte])

object CLValue {
  def from[T: ToBytes](t: T, clType: CLType): CLValue = CLValue(clType, ToBytes.toBytes(t))

  implicit val toBytesCLValue: ToBytes[CLValue] = new ToBytes[CLValue] {
    override def toBytes(v: CLValue): Array[Byte] =
      ToBytes.toBytes(v.value) ++ ToBytes.toBytes(v.clType)
  }

  val deserializer: FromBytes.Deserializer[CLValue] =
    for {
      value  <- FromBytes.bytes
      clType <- CLType.deserializer
    } yield CLValue(clType, value.toIndexedSeq)
}
