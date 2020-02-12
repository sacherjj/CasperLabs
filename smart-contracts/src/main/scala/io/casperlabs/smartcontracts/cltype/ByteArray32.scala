package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}

/** Special type indicating a byte array with exactly 32 elements. */
case class ByteArray32 private (bytes: IndexedSeq[Byte])

object ByteArray32 {
  def apply(bytes: IndexedSeq[Byte]): Option[ByteArray32] =
    if (bytes.length == 32) Some(new ByteArray32(bytes))
    else None

  implicit val toBytes: ToBytes[ByteArray32] = new ToBytes[ByteArray32] {
    override def toBytes(b: ByteArray32): Array[Byte] =
      b.bytes.toArray
  }

  implicit val fromBytes: FromBytes[ByteArray32] = new FromBytes[ByteArray32] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (ByteArray32, BytesView)] =
      FromBytes.safeTake(32, bytes).map {
        case (array, tail) => (new ByteArray32(array.toArray), tail)
      }
  }
}
