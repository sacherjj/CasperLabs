package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}
import io.casperlabs.smartcontracts.cltype

sealed trait StoredValue

object StoredValue {
  case class CLValue(value: cltype.CLValue)      extends StoredValue
  case class Account(account: cltype.Account)    extends StoredValue
  case class Contract(contract: cltype.Contract) extends StoredValue

  implicit val toBytesStoredValue: ToBytes[StoredValue] = new ToBytes[StoredValue] {
    override def toBytes(v: StoredValue): Array[Byte] = v match {
      case CLValue(value)     => CLVALUE_TAG +: ToBytes[cltype.CLValue].toBytes(value)
      case Account(account)   => ACCOUNT_TAG +: ToBytes[cltype.Account].toBytes(account)
      case Contract(contract) => CONTRACT_TAG +: ToBytes[cltype.Contract].toBytes(contract)
    }
  }

  implicit val fromBytesStoredValue: FromBytes[StoredValue] = new FromBytes[StoredValue] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (StoredValue, BytesView)] =
      FromBytes.safePop(bytes).flatMap {
        case (tag, tail) if tag == CLVALUE_TAG =>
          FromBytes[cltype.CLValue].fromBytes(tail).map {
            case (value, rem) => CLValue(value) -> rem
          }

        case (tag, tail) if tag == ACCOUNT_TAG =>
          FromBytes[cltype.Account].fromBytes(tail).map {
            case (account, rem) => Account(account) -> rem
          }

        case (tag, tail) if tag == CONTRACT_TAG =>
          FromBytes[cltype.Contract]
            .fromBytes(tail)
            .map { case (contract, rem) => Contract(contract) -> rem }

        case (other, _) => Left(FromBytes.Error.InvalidVariantTag(other, "StoredValue"))
      }
  }

  val CLVALUE_TAG: Byte  = 0
  val ACCOUNT_TAG: Byte  = 1
  val CONTRACT_TAG: Byte = 2
}
