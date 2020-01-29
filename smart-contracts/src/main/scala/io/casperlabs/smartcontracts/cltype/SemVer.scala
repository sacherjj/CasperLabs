package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}

case class SemVer(major: Int, minor: Int, patch: Int)

object SemVer {
  implicit val toBytesSemVer: ToBytes[SemVer] = new ToBytes[SemVer] {
    override def toBytes(v: SemVer): Array[Byte] =
      ToBytes[Int].toBytes(v.major) ++ ToBytes[Int].toBytes(v.minor) ++ ToBytes[Int].toBytes(
        v.patch
      )
  }

  implicit val fromBytesSemVer: FromBytes[SemVer] = new FromBytes[SemVer] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (SemVer, BytesView)] =
      for {
        (major, rem1) <- FromBytes[Int].fromBytes(bytes)
        (minor, rem2) <- FromBytes[Int].fromBytes(rem1)
        (patch, rem3) <- FromBytes[Int].fromBytes(rem2)
      } yield SemVer(major, minor, patch) -> rem3
  }
}
