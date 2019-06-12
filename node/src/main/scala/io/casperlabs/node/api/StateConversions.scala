package io.casperlabs.node.api

import io.casperlabs.casper.consensus.state
import io.casperlabs.ipc

trait StateConversions {
  def fromIpc(value: ipc.Value): state.Value = {
    import ipc.Value.ValueInstance
    import state.Value.Value

    val v = value.valueInstance match {
      case ValueInstance.Integer(v) =>
        Value.IntValue(v)
      case ValueInstance.ByteArr(v) =>
        Value.BytesValue(v)
      case ValueInstance.IntList(ipc.IntList(vs)) =>
        Value.IntList(state.IntList(vs))
      case ValueInstance.StringVal(v) =>
        Value.StringValue(v)
      case ValueInstance.Account(v) =>
        Value.Account(fromIpc(v))
      case ValueInstance.Contract(v) =>
        Value.Contract(fromIpc(v))
      case ValueInstance.StringList(ipc.StringList(vs)) =>
        Value.StringList(state.StringList(vs))
      case ValueInstance.BigInt(v) =>
        Value.BigInt(state.BigInt(v.value, v.bitWidth))
      case ValueInstance.Key(v) =>
        Value.Key(fromIpc(v))
    }

    state.Value(v)
  }

  private def fromIpc(account: ipc.Account): state.Account =
    state.Account(
      account.pubKey,
      account.nonce,
      account.knownUrefs.map(fromIpc),
      account.associatedKeys.map { x =>
        state.Account.AssociatedKey(x.pubKey, x.weight)
      },
      account.actionThreshold.map { x =>
        state.Account.ActionThresholds(x.deploymentThreshold, x.keyManagementThreshold)
      },
      account.accountActivity.map { x =>
        state.Account
          .AccountActivity(x.keyManagementLastUsed, x.deploymentLastUsed, x.inactivityPeriodLimit)
      }
    )

  private def fromIpc(contract: ipc.Contract): state.Contract =
    state.Contract(
      contract.body,
      contract.knownUrefs.map(fromIpc),
      contract.getProtocolVersion.version
    )

  private def fromIpc(namedKey: ipc.NamedKey): state.NamedKey =
    state.NamedKey(namedKey.name, namedKey.key.map(fromIpc))

  private def fromIpc(key: ipc.Key): state.Key = {
    import ipc.Key.KeyInstance
    import state.Key.Value

    val v = key.keyInstance match {
      case KeyInstance.Account(ipc.KeyAddress(v)) =>
        Value.Address(state.Key.Address(v))
      case KeyInstance.Hash(ipc.KeyHash(v)) =>
        Value.Hash(state.Key.Hash(v))
      case KeyInstance.Uref(ipc.KeyURef(v, r)) =>
        Value.Uref(state.Key.URef(v, state.Key.URef.AccessRights.fromValue(r.value)))
      case KeyInstance.Local(ipc.KeyLocal(s, h)) =>
        Value.Local(state.Key.Local(s, h))
    }

    state.Key(v)
  }

}
