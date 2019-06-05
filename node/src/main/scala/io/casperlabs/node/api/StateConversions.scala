package io.casperlabs.node.api

import io.casperlabs.casper.consensus.info.State
import io.casperlabs.ipc

trait StateConversions {
  def fromIpc(value: ipc.Value): State.Value = {
    import ipc.Value.ValueInstance
    import State.Value.Value

    val v = value.valueInstance match {
      case ValueInstance.Integer(v) =>
        Value.IntValue(v)
      case ValueInstance.ByteArr(v) =>
        Value.BytesValue(v)
      case ValueInstance.IntList(ipc.IntList(vs)) =>
        Value.IntList(State.IntList(vs))
      case ValueInstance.StringVal(v) =>
        Value.StringValue(v)
      case ValueInstance.Account(v) =>
        Value.Account(fromIpc(v))
      case ValueInstance.Contract(v) =>
        Value.Contract(fromIpc(v))
      case ValueInstance.StringList(ipc.StringList(vs)) =>
        Value.StringList(State.StringList(vs))
      case ValueInstance.BigInt(v) =>
        Value.BigInt(State.BigInt(v.value, v.bitWidth))
      case ValueInstance.Key(v) =>
        Value.Key(fromIpc(v))
    }

    State.Value(v)
  }

  private def fromIpc(account: ipc.Account): State.Account =
    State.Account(
      account.pubKey,
      account.nonce,
      account.knownUrefs.map(fromIpc),
      account.associatedKeys.map { x =>
        State.Account.AssociatedKey(x.pubKey, x.weight)
      },
      account.actionThreshold.map { x =>
        State.Account.ActionThresholds(x.deploymentThreshold, x.keyManagementThreshold)
      },
      account.accountActivity.map { x =>
        State.Account
          .AccountActivity(x.keyManagementLastUsed, x.deploymentLastUsed, x.inactivityPeriodLimit)
      }
    )

  private def fromIpc(contract: ipc.Contract): State.Contract =
    State.Contract(
      contract.body,
      contract.knownUrefs.map(fromIpc),
      contract.getProtocolVersion.version
    )

  private def fromIpc(namedKey: ipc.NamedKey): State.NamedKey =
    State.NamedKey(namedKey.name, namedKey.key.map(fromIpc))

  private def fromIpc(key: ipc.Key): State.Key = {
    import ipc.Key.KeyInstance
    import State.Key.Value

    val v = key.keyInstance match {
      case KeyInstance.Account(ipc.KeyAddress(v)) =>
        Value.Address(State.Key.Address(v))
      case KeyInstance.Hash(ipc.KeyHash(v)) =>
        Value.Hash(State.Key.Hash(v))
      case KeyInstance.Uref(ipc.KeyURef(v, r)) =>
        Value.Uref(State.Key.URef(v, State.Key.URef.AccessRights.fromValue(r.value)))
      case KeyInstance.Local(ipc.KeyLocal(s, h)) =>
        Value.Local(State.Key.Local(s, h))
    }

    State.Key(v)
  }

}
