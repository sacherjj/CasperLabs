package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

case class SemVer(major: Int, minor: Int, patch: Int)

object SemVer {
  implicit val toBytesSemVer: ToBytes[SemVer] = new ToBytes[SemVer] {
    override def toBytes(v: SemVer): Array[Byte] =
      ToBytes.toBytes(v.major) ++ ToBytes.toBytes(v.minor) ++ ToBytes.toBytes(v.patch)
  }

  val deserializer: FromBytes.Deserializer[SemVer] =
    for {
      major <- FromBytes.int
      minor <- FromBytes.int
      patch <- FromBytes.int
    } yield SemVer(major, minor, patch)
}
