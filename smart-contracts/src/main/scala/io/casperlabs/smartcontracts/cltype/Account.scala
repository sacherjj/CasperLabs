package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}
import Account.{ActionThresholds, PublicKey, Weight}

case class Account(
    publicKey: PublicKey,
    namedKeys: Map[String, Key],
    mainPurse: URef,
    associatedKeys: Map[PublicKey, Weight],
    actionThresholds: ActionThresholds
)

object Account {
  type PublicKey = ByteArray32
  type Weight    = Byte
  case class ActionThresholds(deployment: Weight, keyManagement: Weight)

  implicit val toBytesActionThresholds: ToBytes[ActionThresholds] = new ToBytes[ActionThresholds] {
    override def toBytes(a: ActionThresholds): Array[Byte] =
      ToBytes.toBytes(a.deployment -> a.keyManagement)
  }

  implicit val fromBytesActionThresholds: FromBytes[ActionThresholds] =
    new FromBytes[ActionThresholds] {
      override def fromBytes(
          bytes: BytesView
      ): Either[FromBytes.Error, (ActionThresholds, BytesView)] =
        FromBytes[(Weight, Weight)].fromBytes(bytes).map {
          case ((deployment, keyManagement), tail) =>
            ActionThresholds(deployment, keyManagement) -> tail
        }
    }

  private implicit val publicKeyOrdering = Ordering.fromLessThan[PublicKey] {
    case (k1, k2) =>
      k1.bytes.length < k2.bytes.length ||
        (k1.bytes.length == k2.bytes.length && k1.bytes
          .zip(k2.bytes)
          .dropWhile { case (b1, b2) => b1 == b2 }
          .headOption
          .exists { case (b1, b2) => b1 < b2 })
  }

  implicit val toBytesAccount: ToBytes[Account] = new ToBytes[Account] {
    override def toBytes(a: Account): Array[Byte] =
      ToBytes.toBytes(a.publicKey) ++
        ToBytes.toBytes(a.namedKeys) ++
        ToBytes.toBytes(a.mainPurse) ++
        ToBytes.toBytes(a.associatedKeys) ++
        ToBytes.toBytes(a.actionThresholds)
  }

  implicit val fromBytesAccount: FromBytes[Account] = new FromBytes[Account] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Account, BytesView)] =
      for {
        (publicKey, rem1)        <- FromBytes[PublicKey].fromBytes(bytes)
        (namedKeys, rem2)        <- FromBytes[Map[String, Key]].fromBytes(rem1)
        (mainPurse, rem3)         <- FromBytes[URef].fromBytes(rem2)
        (associatedKeys, rem4)   <- FromBytes[Map[PublicKey, Weight]].fromBytes(rem3)
        (actionThresholds, rem5) <- FromBytes[ActionThresholds].fromBytes(rem4)
      } yield Account(publicKey, namedKeys, mainPurse, associatedKeys, actionThresholds) -> rem5
  }
}
