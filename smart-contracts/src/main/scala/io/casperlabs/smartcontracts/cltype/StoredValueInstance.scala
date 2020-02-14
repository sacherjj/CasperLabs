package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.FromBytes
import io.casperlabs.smartcontracts.cltype

sealed trait StoredValueInstance

object StoredValueInstance {
  def from(sv: StoredValue): Either[FromBytes.Error, StoredValueInstance] = sv match {
    case StoredValue.CLValue(v) =>
      cltype.CLValue.instantiate(v).map(instance => CLValue(instance))
    case StoredValue.Account(a)  => Right(Account(a))
    case StoredValue.Contract(c) => Right(Contract(c))
  }

  case class CLValue(value: cltype.CLValueInstance) extends StoredValueInstance
  case class Account(account: cltype.Account)       extends StoredValueInstance
  case class Contract(contract: cltype.Contract)    extends StoredValueInstance
}
