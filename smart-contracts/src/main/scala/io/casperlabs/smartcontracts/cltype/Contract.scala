package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}

case class Contract(bytes: IndexedSeq[Byte], namedKeys: Map[String, Key], protocolVersion: SemVer)

object Contract {
  implicit val toBytesContract: ToBytes[Contract] = new ToBytes[Contract] {
    override def toBytes(c: Contract): Array[Byte] =
      ToBytes[Seq[Byte]].toBytes(c.bytes) ++ ToBytes[Map[String, Key]]
        .toBytes(c.namedKeys) ++ ToBytes[SemVer].toBytes(
        c.protocolVersion
      )
  }

  implicit val fromBytesContract: FromBytes[Contract] = new FromBytes[Contract] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Contract, BytesView)] =
      for {
        (contractBytes, rem1)   <- FromBytes[Seq[Byte]].fromBytes(bytes)
        (namedKeys, rem2)       <- FromBytes[Map[String, Key]].fromBytes(rem1)
        (protocolVersion, rem3) <- FromBytes[SemVer].fromBytes(rem2)
      } yield Contract(contractBytes.toIndexedSeq, namedKeys, protocolVersion) -> rem3
  }
}
