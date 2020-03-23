package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}
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

  val desActionThresholds: FromBytes.Deserializer[ActionThresholds] =
    FromBytes.tuple2(FromBytes.byte, FromBytes.byte).map {
      case (deployment, keyManagement) => ActionThresholds(deployment, keyManagement)
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

  val deserializer: FromBytes.Deserializer[Account] =
    for {
      publicKey        <- ByteArray32.deserializer
      namedKeys        <- FromBytes.map(FromBytes.string, Key.deserializer)
      mainPurse        <- URef.deserializer
      associatedKeys   <- FromBytes.map(ByteArray32.deserializer, FromBytes.byte)
      actionThresholds <- desActionThresholds
    } yield Account(publicKey, namedKeys, mainPurse, associatedKeys, actionThresholds)
}
