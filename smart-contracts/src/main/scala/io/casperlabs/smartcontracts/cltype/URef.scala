package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr._

case class URef(address: ByteArray32, accessRights: AccessRights)

object URef {
  implicit val toBytesURef: ToBytes[URef] = new ToBytes[URef] {
    override def toBytes(u: URef): Array[Byte] =
      ToBytes.toBytes(u.address) ++ ToBytes.toBytes(u.accessRights)
  }

  val deserializer: FromBytes.Deserializer[URef] =
    for {
      address      <- ByteArray32.deserializer
      accessRights <- AccessRights.deserializer
    } yield URef(address, accessRights)
}
