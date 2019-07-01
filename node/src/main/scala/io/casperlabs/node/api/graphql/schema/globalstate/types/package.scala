package io.casperlabs.node.api.graphql.schema.globalstate

import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.state
import sangria.schema._

package object types {
  // Everything defined as ObjectTypes because
  // sangria doesn't support UnionTypes created from with ScalarTypes.

  lazy val AccessRightsEnum: EnumType[state.Key.URef.AccessRights] =
    EnumType[state.Key.URef.AccessRights](
      "AccessRightsType",
      values = List(
        EnumValue(
          "UNKNOWN",
          value = state.Key.URef.AccessRights.UNKNOWN
        ),
        EnumValue(
          "READ",
          value = state.Key.URef.AccessRights.READ
        ),
        EnumValue(
          "WRITE",
          value = state.Key.URef.AccessRights.WRITE
        ),
        EnumValue(
          "ADD",
          value = state.Key.URef.AccessRights.ADD
        ),
        EnumValue(
          "READ_ADD",
          value = state.Key.URef.AccessRights.READ_ADD
        ),
        EnumValue(
          "READ_WRITE",
          value = state.Key.URef.AccessRights.READ_WRITE
        ),
        EnumValue(
          "ADD_WRITE",
          value = state.Key.URef.AccessRights.ADD_WRITE
        ),
        EnumValue(
          "READ_ADD_WRITE",
          value = state.Key.URef.AccessRights.READ_ADD_WRITE
        )
      )
    )

  lazy val KeyAddress =
    ObjectType(
      "KeyAddress",
      fields[Unit, state.Key.Address](
        Field(
          "value",
          StringType,
          resolve = c => Base16.encode(c.value.account.toByteArray)
        )
      )
    )

  lazy val KeyHash = ObjectType(
    "KeyHash",
    fields[Unit, state.Key.Hash](
      Field(
        "value",
        StringType,
        resolve = c => Base16.encode(c.value.hash.toByteArray)
      )
    )
  )

  lazy val KeyURef = ObjectType(
    "KeyUref",
    fields[Unit, state.Key.URef](
      Field(
        "uref",
        StringType,
        resolve = c => Base16.encode(c.value.uref.toByteArray)
      ),
      Field(
        "accessRights",
        AccessRightsEnum,
        resolve = _.value.accessRights
      )
    )
  )

  lazy val KeyLocal = ObjectType(
    "KeyLocal",
    fields[Unit, state.Key.Local](
      Field(
        "hash",
        StringType,
        resolve = c => Base16.encode(c.value.hash.toByteArray)
      )
    )
  )

  lazy val KeyUnion = UnionType(
    "KeyUnion",
    types = List(
      KeyAddress,
      KeyHash,
      KeyURef,
      KeyLocal
    )
  )

  lazy val KeyType = ObjectType(
    "Key",
    fields[Unit, state.Key](
      Field(
        "value",
        KeyUnion,
        resolve = _.value.value match {
          case state.Key.Value.Local(value)   => value
          case state.Key.Value.Hash(value)    => value
          case state.Key.Value.Address(value) => value
          case state.Key.Value.Uref(value)    => value
          case state.Key.Value.Empty          => ???
        }
      )
    )
  )

  lazy val NamedKey = ObjectType(
    "NamedKey",
    fields[Unit, state.NamedKey](
      Field("name", StringType, resolve = _.value.name),
      Field("key", KeyType, resolve = _.value.key.get)
    )
  )

  lazy val Contract = ObjectType(
    "Contract",
    fields[Unit, state.Contract](
      Field("body", StringType, resolve = c => Base16.encode(c.value.body.toByteArray)),
      Field("knownUrefs", ListType(NamedKey), resolve = _.value.knownUrefs),
      Field("protocolVersion", LongType, resolve = _.value.protocolVersion.get.value)
    )
  )

  lazy val AccountAssociatedKey = ObjectType(
    "AccountAssociatedKey",
    fields[Unit, state.Account.AssociatedKey](
      Field(
        "pubKey",
        StringType,
        resolve = c => Base16.encode(c.value.publicKey.toByteArray)
      ),
      Field("weight", IntType, resolve = _.value.weight)
    )
  )

  lazy val AccountActionThresholds = ObjectType(
    "AccountActionThresholds",
    fields[Unit, state.Account.ActionThresholds](
      Field("deploymentThreshold", IntType, resolve = _.value.deploymentThreshold),
      Field("keyManagementThreshold", IntType, resolve = _.value.keyManagementThreshold)
    )
  )

  lazy val AccountAccountActivity = ObjectType(
    "AccountAccountActivity",
    fields[Unit, state.Account.AccountActivity](
      Field("keyManagementLastUsed", LongType, resolve = _.value.keyManagementLastUsed),
      Field("deploymentLastUsed", LongType, resolve = _.value.deploymentLastUsed),
      Field("inactivityPeriodLimit", LongType, resolve = _.value.inactivityPeriodLimit)
    )
  )

  lazy val Account = ObjectType(
    "Account",
    fields[Unit, state.Account](
      Field("pubKey", StringType, resolve = c => Base16.encode(c.value.publicKey.toByteArray)),
      Field("nonce", LongType, resolve = _.value.nonce),
      Field("purseId", KeyURef, resolve = _.value.purseId.get),
      Field("knownUrefs", ListType(NamedKey), resolve = _.value.knownUrefs),
      Field("associatedKeys", ListType(AccountAssociatedKey), resolve = _.value.associatedKeys),
      Field("actionThreshold", AccountActionThresholds, resolve = _.value.actionThresholds.get),
      Field("accountActivity", AccountAccountActivity, resolve = _.value.accountActivity.get)
    )
  )

  lazy val ValueInt =
    ObjectType(
      "IntValue",
      fields[Unit, state.Value.Value.IntValue](
        Field(
          "value",
          IntType,
          resolve = _.value.value
        )
      )
    )

  lazy val ValueByteArray =
    ObjectType(
      "ByteString",
      fields[Unit, state.Value.Value.BytesValue](
        Field(
          "value",
          StringType,
          resolve = c => Base16.encode(c.value.value.toByteArray)
        )
      )
    )

  lazy val IntList =
    ObjectType(
      "IntList",
      fields[Unit, state.IntList](
        Field("value", ListType(IntType), resolve = _.value.values)
      )
    )

  lazy val ValueString =
    ObjectType(
      "StringValue",
      fields[Unit, state.Value.Value.StringValue](
        Field("value", StringType, resolve = _.value.value)
      )
    )

  lazy val StringList =
    ObjectType(
      "StringList",
      fields[Unit, state.StringList](
        Field("value", ListType(StringType), resolve = _.value.values)
      )
    )

  lazy val RustBigInt =
    ObjectType(
      "BigIntValue",
      fields[Unit, state.BigInt](
        Field("value", StringType, resolve = _.value.value),
        Field("bitWidth", IntType, resolve = _.value.bitWidth)
      )
    )

  lazy val UnitType = ObjectType(
    "Unit",
    fields[Unit, state.Unit](
      Field("value", StringType, resolve = _ => "Unit")
    )
  )

  lazy val ValueLong = ObjectType(
    "LongValue",
    fields[Unit, state.Value.Value.LongValue](
      Field("value", LongType, resolve = _.value.value)
    )
  )

  lazy val ValueUnion = UnionType(
    "ValueUnion",
    types = List(
      ValueInt,
      ValueByteArray,
      ValueLong,
      IntList,
      ValueString,
      Account,
      Contract,
      StringList,
      NamedKey,
      RustBigInt,
      KeyType,
      UnitType
    )
  )

  lazy val Value = ObjectType(
    "Value",
    fields[Unit, state.Value](
      Field(
        "value",
        ValueUnion,
        resolve = _.value.value match {
          case state.Value.Value.Contract(value)    => value
          case state.Value.Value.BytesValue(value)  => value
          case state.Value.Value.BigInt(value)      => value
          case state.Value.Value.LongValue(value)   => value
          case value: state.Value.Value.StringValue => value
          case state.Value.Value.Key(value)         => value
          case state.Value.Value.Unit(value)        => value
          case value: state.Value.Value.IntValue    => value
          case state.Value.Value.NamedKey(value)    => value
          case state.Value.Value.Account(value)     => value
          case state.Value.Value.StringList(value)  => value
          case state.Value.Value.IntList(value)     => value
          case state.Value.Value.Empty              => ???
        }
      )
    )
  )
}
