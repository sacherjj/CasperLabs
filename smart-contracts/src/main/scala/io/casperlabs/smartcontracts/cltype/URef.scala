package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr._

case class URef(address: ByteArray32, accessRights: Option[AccessRights])

object URef {
  implicit val toBytesURef: ToBytes[URef] = new ToBytes[URef] {
    override def toBytes(u: URef): Array[Byte] =
      ToBytes[ByteArray32].toBytes(u.address) ++ ToBytes[Option[AccessRights]]
        .toBytes(u.accessRights)
  }

  implicit val fromBytesURef: FromBytes[URef] = new FromBytes[URef] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (URef, BytesView)] =
      for {
        (address, tail)     <- FromBytes[ByteArray32].fromBytes(bytes)
        (accessRights, rem) <- FromBytes[Option[AccessRights]].fromBytes(tail)
      } yield URef(address, accessRights) -> rem
  }

  implicit val clTypedURef: CLTyped[URef] = new CLTyped[URef] {
    override def clType: CLType = CLType.URef
  }
}
