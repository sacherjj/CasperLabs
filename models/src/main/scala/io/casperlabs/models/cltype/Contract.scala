package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

case class Contract(bytes: IndexedSeq[Byte], namedKeys: Map[String, Key], protocolVersion: SemVer)

object Contract {
  implicit val toBytesContract: ToBytes[Contract] = new ToBytes[Contract] {
    override def toBytes(c: Contract): Array[Byte] =
      ToBytes.toBytes(c.bytes) ++ ToBytes.toBytes(c.namedKeys) ++ ToBytes.toBytes(c.protocolVersion)
  }

  val deserializer: FromBytes.Deserializer[Contract] =
    for {
      contractBytes   <- FromBytes.bytes
      namedKeys       <- FromBytes.map(FromBytes.string, Key.deserializer)
      protocolVersion <- SemVer.deserializer
    } yield Contract(contractBytes.toIndexedSeq, namedKeys, protocolVersion)
}
