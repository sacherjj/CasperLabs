package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr._

case class URef(address: ByteArray32, accessRights: AccessRights)

object URef {
  implicit val toBytesURef: ToBytes[URef] = new ToBytes[URef] {
    override def toBytes(u: URef): Array[Byte] =
      ToBytes.toBytes(u.address) ++ ToBytes.toBytes(u.accessRights)
  }

  def lt(x: URef, y: URef): Boolean =
    ByteArray32.lt(x.address, y.address) || (
      (x.address == y.address) && (x.accessRights.tag < y.accessRights.tag)
    )

  val deserializer: FromBytes.Deserializer[URef] =
    for {
      address      <- ByteArray32.deserializer
      accessRights <- AccessRights.deserializer
    } yield URef(address, accessRights)
}
