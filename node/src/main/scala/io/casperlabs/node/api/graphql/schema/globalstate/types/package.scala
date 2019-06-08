package io.casperlabs.node.api.graphql.schema.globalstate

import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import sangria.schema._

package object types {
  // Everything defined as ObjectTypes because
  // sangria doesn't support UnionTypes created from with ScalarTypes.

  lazy val AccessRightsEnum: EnumType[ipc.KeyURef.AccessRights] =
    EnumType[ipc.KeyURef.AccessRights](
      "AccessRightsType",
      values = List(
        EnumValue(
          "UNKNOWN",
          value = ipc.KeyURef.AccessRights.UNKNOWN
        ),
        EnumValue(
          "READ",
          value = ipc.KeyURef.AccessRights.READ
        ),
        EnumValue(
          "WRITE",
          value = ipc.KeyURef.AccessRights.WRITE
        ),
        EnumValue(
          "ADD",
          value = ipc.KeyURef.AccessRights.ADD
        ),
        EnumValue(
          "READ_ADD",
          value = ipc.KeyURef.AccessRights.READ_ADD
        ),
        EnumValue(
          "READ_WRITE",
          value = ipc.KeyURef.AccessRights.READ_WRITE
        ),
        EnumValue(
          "ADD_WRITE",
          value = ipc.KeyURef.AccessRights.ADD_WRITE
        ),
        EnumValue(
          "READ_ADD_WRITE",
          value = ipc.KeyURef.AccessRights.READ_ADD_WRITE
        )
      )
    )

  lazy val KeyAddress =
    ObjectType(
      "KeyAddress",
      fields[Unit, ipc.KeyAddress](
        Field(
          "value",
          StringType,
          resolve = c => Base16.encode(c.value.account.toByteArray)
        )
      )
    )

  lazy val KeyHash = ObjectType(
    "KeyHash",
    fields[Unit, ipc.KeyHash](
      Field(
        "value",
        StringType,
        resolve = c => Base16.encode(c.value.key.toByteArray)
      )
    )
  )

  lazy val KeyURef = ObjectType(
    "KeyUref",
    fields[Unit, ipc.KeyURef](
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
    fields[Unit, ipc.KeyLocal](
      Field(
        "seed",
        StringType,
        resolve = c => Base16.encode(c.value.seed.toByteArray)
      ),
      Field(
        "keyHash",
        StringType,
        resolve = c => Base16.encode(c.value.keyHash.toByteArray)
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

  lazy val Key = ObjectType(
    "Key",
    fields[Unit, ipc.Key](
      Field(
        "value",
        KeyUnion,
        resolve = _.value.keyInstance match {
          case ipc.Key.KeyInstance.Account(value) => value
          case ipc.Key.KeyInstance.Hash(value)    => value
          case ipc.Key.KeyInstance.Uref(value)    => value
          case ipc.Key.KeyInstance.Local(value)   => value
          case ipc.Key.KeyInstance.Empty          => ???
        }
      )
    )
  )

  lazy val NamedKey = ObjectType(
    "NamedKey",
    fields[Unit, ipc.NamedKey](
      Field("name", StringType, resolve = _.value.name),
      Field("key", Key, resolve = _.value.key.get)
    )
  )

  lazy val Contract = ObjectType(
    "Contract",
    fields[Unit, ipc.Contract](
      Field("body", StringType, resolve = c => Base16.encode(c.value.body.toByteArray)),
      Field("knownUrefs", ListType(NamedKey), resolve = _.value.knownUrefs),
      Field("protocolVersion", LongType, resolve = _.value.protocolVersion.get.version)
    )
  )

  lazy val AccountAssociatedKey = ObjectType(
    "AccountAssociatedKey",
    fields[Unit, ipc.Account.AssociatedKey](
      Field(
        "pubKey",
        StringType,
        resolve = c => Base16.encode(c.value.pubKey.toByteArray)
      ),
      Field("weight", IntType, resolve = _.value.weight)
    )
  )

  lazy val AccountActionThresholds = ObjectType(
    "AccountActionThresholds",
    fields[Unit, ipc.Account.ActionThresholds](
      Field("deploymentThreshold", IntType, resolve = _.value.deploymentThreshold),
      Field("keyManagementThreshold", IntType, resolve = _.value.keyManagementThreshold)
    )
  )

  lazy val AccountAccountActivity = ObjectType(
    "AccountAccountActivity",
    fields[Unit, ipc.Account.AccountActivity](
      Field("keyManagementLastUsed", LongType, resolve = _.value.keyManagementLastUsed),
      Field("deploymentLastUsed", LongType, resolve = _.value.deploymentLastUsed),
      Field("inactivityPeriodLimit", LongType, resolve = _.value.inactivityPeriodLimit)
    )
  )

  lazy val Account = ObjectType(
    "Account",
    fields[Unit, ipc.Account](
      Field("pubKey", StringType, resolve = c => Base16.encode(c.value.pubKey.toByteArray)),
      Field("nonce", LongType, resolve = _.value.nonce),
      Field("knownUrefs", ListType(NamedKey), resolve = _.value.knownUrefs),
      Field("associatedKeys", ListType(AccountAssociatedKey), resolve = _.value.associatedKeys),
      Field("actionThreshold", AccountActionThresholds, resolve = _.value.actionThreshold.get),
      Field("accountActivity", AccountAccountActivity, resolve = _.value.accountActivity.get)
    )
  )

  lazy val ValueInt =
    ObjectType(
      "IntValue",
      fields[Unit, ipc.Value.ValueInstance.Integer](
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
      fields[Unit, ipc.Value.ValueInstance.ByteArr](
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
      fields[Unit, ipc.IntList](
        Field("value", ListType(IntType), resolve = _.value.list)
      )
    )

  lazy val ValueString =
    ObjectType(
      "StringValue",
      fields[Unit, ipc.Value.ValueInstance.StringVal](
        Field("value", StringType, resolve = _.value.value)
      )
    )

  lazy val StringList =
    ObjectType(
      "StringList",
      fields[Unit, ipc.StringList](
        Field("value", ListType(StringType), resolve = _.value.list)
      )
    )

  lazy val RustBigInt =
    ObjectType(
      "BigIntValue",
      fields[Unit, ipc.RustBigInt](
        Field("value", StringType, resolve = _.value.value),
        Field("bitWidth", IntType, resolve = _.value.bitWidth)
      )
    )

  lazy val UnitValue = ObjectType("Unit", fields[Unit, ipc.UnitValue]())

  lazy val ValueUnion = UnionType(
    "ValueUnion",
    types = List(
      ValueInt,
      ValueByteArray,
      IntList,
      ValueString,
      Account,
      Contract,
      StringList,
      NamedKey,
      RustBigInt,
      Key
    )
  )

  lazy val Value = ObjectType(
    "Value",
    fields[Unit, ipc.Value](
      Field(
        "value",
        ValueUnion,
        resolve = _.value.valueInstance match {
          case ipc.Value.ValueInstance.Empty             => ???
          case value: ipc.Value.ValueInstance.Integer    => value
          case value: ipc.Value.ValueInstance.ByteArr    => value
          case ipc.Value.ValueInstance.IntList(value)    => value
          case value: ipc.Value.ValueInstance.StringVal  => value
          case ipc.Value.ValueInstance.Account(value)    => value
          case ipc.Value.ValueInstance.Contract(value)   => value
          case ipc.Value.ValueInstance.StringList(value) => value
          case ipc.Value.ValueInstance.NamedKey(value)   => value
          case ipc.Value.ValueInstance.BigInt(value)     => value
          case ipc.Value.ValueInstance.Key(value)        => value
          case ipc.Value.ValueInstance.Unit(value)       => value
        }
      )
    )
  )
}
