package io.casperlabs.smartcontracts.cltype

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state
import io.casperlabs.smartcontracts.bytesrepr.FromBytes

object ProtoMappings {
  def toProto(v: StoredValue): Option[state.Value] = v match {
    case StoredValue.CLValue(clValue) => toProto(clValue)

    case StoredValue.Account(account) =>
      Some(state.Value(state.Value.Value.Account(toProto(account))))

    case StoredValue.Contract(contract) =>
      Some(state.Value(state.Value.Value.Contract(toProto(contract))))
  }

  def toProto(c: Contract): state.Contract = state.Contract(
    ByteString.copyFrom(c.bytes.toArray),
    toProto(c.namedKeys),
    Some(toProto(c.protocolVersion))
  )

  def toProto(a: Account): state.Account = state.Account(
    ByteString.copyFrom(a.publicKey.bytes.toArray),
    toProto(Key.URef(a.purseId)).value.uref,
    toProto(a.namedKeys),
    a.associatedKeys.toSeq.map {
      case (k, w) => state.Account.AssociatedKey(ByteString.copyFrom(k.bytes.toArray), w.toInt)
    },
    Some(
      state.Account
        .ActionThresholds(
          a.actionThresholds.deployment.toInt,
          a.actionThresholds.keyManagement.toInt
        )
    )
  )

  def toProto(namedKeys: Map[String, Key]): Seq[state.NamedKey] = namedKeys.toSeq.map {
    case (n, k) => state.NamedKey(n, Some(toProto(k)))
  }

  def toProto(k: Key): state.Key = k match {
    case Key.Account(address) =>
      state.Key(
        state.Key.Value.Address(state.Key.Address(ByteString.copyFrom(address.bytes.toArray)))
      )

    case Key.Hash(address) =>
      state.Key(
        state.Key.Value.Hash(state.Key.Hash(ByteString.copyFrom(address.bytes.toArray)))
      )

    case Key.URef(uref) =>
      state.Key(
        state.Key.Value.Uref(state.Key.URef(ByteString.copyFrom(uref.address.bytes.toArray)))
      )

    case Key.Local(address) =>
      state.Key(
        state.Key.Value.Local(state.Key.Local(ByteString.copyFrom(address.bytes.toArray)))
      )
  }

  def toProto(version: SemVer): state.ProtocolVersion = state.ProtocolVersion(
    version.major,
    version.minor,
    version.patch
  )

  def toProto(v: CLValue): Option[state.Value] = v.clType match {
    case CLType.I32 | CLType.U32 =>
      FromBytes.deserialize[Int](v.value.toArray).toOption.map { i =>
        state.Value(state.Value.Value.IntValue(i))
      }

    case CLType.List(CLType.U8) =>
      v.to[Seq[Byte]].toOption.map { bytes =>
        state.Value(state.Value.Value.BytesValue(ByteString.copyFrom(bytes.toArray)))
      }

    case CLType.List(CLType.I32) | CLType.List(CLType.U32) =>
      FromBytes.deserialize[Seq[Int]](v.value.toArray).toOption.map { list =>
        state.Value(state.Value.Value.IntList(state.IntList(list)))
      }

    case CLType.String =>
      v.to[String].toOption.map { s =>
        state.Value(state.Value.Value.StringValue(s))
      }

    case CLType.List(CLType.String) =>
      v.to[Seq[String]].toOption.map { list =>
        state.Value(state.Value.Value.StringList(state.StringList(list)))
      }

    case CLType.Tuple2(CLType.String, CLType.Key) =>
      v.to[(String, Key)].toOption.map {
        case (n, k) => state.Value(state.Value.Value.NamedKey(state.NamedKey(n, Some(toProto(k)))))
      }

    case CLType.U128 =>
      FromBytes.deserialize[BigInt](v.value.toArray).toOption.map { i =>
        state.Value(state.Value.Value.BigInt(state.BigInt(i.toString, 128)))
      }

    case CLType.U256 =>
      FromBytes.deserialize[BigInt](v.value.toArray).toOption.map { i =>
        state.Value(state.Value.Value.BigInt(state.BigInt(i.toString, 256)))
      }

    case CLType.U512 =>
      FromBytes.deserialize[BigInt](v.value.toArray).toOption.map { i =>
        state.Value(state.Value.Value.BigInt(state.BigInt(i.toString, 512)))
      }

    case CLType.Key =>
      v.to[Key].toOption.map { k =>
        state.Value(state.Value.Value.Key(toProto(k)))
      }

    case CLType.URef =>
      v.to[URef].toOption.map { u =>
        val key = Key.URef(u)
        state.Value(state.Value.Value.Key(toProto(key)))
      }

    case CLType.Unit =>
      Some(state.Value(state.Value.Value.Unit(state.Unit())))

    case CLType.I64 | CLType.U64 =>
      FromBytes.deserialize[Long](v.value.toArray).toOption.map { i =>
        state.Value(state.Value.Value.LongValue(i))
      }

    case CLType.Bool            => None
    case CLType.U8              => None
    case CLType.Option(_)       => None
    case CLType.List(_)         => None
    case CLType.FixedList(_, _) => None
    case CLType.Result(_, _)    => None
    case CLType.Map(_, _)       => None
    case CLType.Tuple1(_)       => None
    case CLType.Tuple2(_, _)    => None
    case CLType.Tuple3(_, _, _) => None
    case CLType.Any             => None
  }
}
