package io.casperlabs.smartcontracts.cltype

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state
import io.casperlabs.smartcontracts.bytesrepr.FromBytes

object ProtoMappings {
  def toProto(v: StoredValue): Either[Error, state.Value] = v match {
    case StoredValue.CLValue(clValue) => toProto(clValue)

    case StoredValue.Account(account) =>
      Right(state.Value(state.Value.Value.Account(toProto(account))))

    case StoredValue.Contract(contract) =>
      Right(state.Value(state.Value.Value.Contract(toProto(contract))))
  }

  def toProto(c: Contract): state.Contract = state.Contract(
    body = ByteString.copyFrom(c.bytes.toArray),
    namedKeys = toProto(c.namedKeys),
    protocolVersion = Some(toProto(c.protocolVersion))
  )

  def toProto(a: Account): state.Account = state.Account(
    publicKey = ByteString.copyFrom(a.publicKey.bytes.toArray),
    purseId = toProto(Key.URef(a.purseId)).value.uref,
    namedKeys = toProto(a.namedKeys),
    associatedKeys = a.associatedKeys.toSeq.map {
      case (k, w) => state.Account.AssociatedKey(ByteString.copyFrom(k.bytes.toArray), w.toInt)
    },
    actionThresholds = Some(
      state.Account
        .ActionThresholds(
          deploymentThreshold = a.actionThresholds.deployment.toInt,
          keyManagementThreshold = a.actionThresholds.keyManagement.toInt
        )
    )
  )

  def toProto(namedKeys: Map[String, Key]): Seq[state.NamedKey] = namedKeys.toSeq.map {
    case (n, k) => state.NamedKey(name = n, key = Some(toProto(k)))
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
    major = version.major,
    minor = version.minor,
    patch = version.patch
  )

  def toProto(v: CLValue): Either[Error, state.Value] = v.clType match {
    case CLType.I32 | CLType.U32 =>
      FromBytes
        .deserialize[Int](v.value.toArray)
        .map { i =>
          state.Value(state.Value.Value.IntValue(i))
        }
        .leftMap(err => Error.FromBytesError(err))

    case CLType.List(CLType.U8) =>
      v.to[Seq[Byte]]
        .map { bytes =>
          state.Value(state.Value.Value.BytesValue(ByteString.copyFrom(bytes.toArray)))
        }
        .leftMap(err => Error.CLValueError(err))

    case CLType.List(CLType.I32) | CLType.List(CLType.U32) =>
      FromBytes
        .deserialize[Seq[Int]](v.value.toArray)
        .map { list =>
          state.Value(state.Value.Value.IntList(state.IntList(list)))
        }
        .leftMap(err => Error.FromBytesError(err))

    case CLType.String =>
      v.to[String]
        .map { s =>
          state.Value(state.Value.Value.StringValue(s))
        }
        .leftMap(err => Error.CLValueError(err))

    case CLType.List(CLType.String) =>
      v.to[Seq[String]]
        .map { list =>
          state.Value(state.Value.Value.StringList(state.StringList(list)))
        }
        .leftMap(err => Error.CLValueError(err))

    case CLType.Tuple2(CLType.String, CLType.Key) =>
      v.to[(String, Key)]
        .map {
          case (n, k) =>
            state
              .Value(state.Value.Value.NamedKey(state.NamedKey(name = n, key = Some(toProto(k)))))
        }
        .leftMap(err => Error.CLValueError(err))

    case CLType.U128 =>
      FromBytes
        .deserialize[BigInt](v.value.toArray)
        .map { i =>
          state.Value(state.Value.Value.BigInt(state.BigInt(value = i.toString, bitWidth = 128)))
        }
        .leftMap(err => Error.FromBytesError(err))

    case CLType.U256 =>
      FromBytes
        .deserialize[BigInt](v.value.toArray)
        .map { i =>
          state.Value(state.Value.Value.BigInt(state.BigInt(value = i.toString, bitWidth = 256)))
        }
        .leftMap(err => Error.FromBytesError(err))

    case CLType.U512 =>
      FromBytes
        .deserialize[BigInt](v.value.toArray)
        .map { i =>
          state.Value(state.Value.Value.BigInt(state.BigInt(value = i.toString, bitWidth = 512)))
        }
        .leftMap(err => Error.FromBytesError(err))

    case CLType.Key =>
      v.to[Key]
        .map { k =>
          state.Value(state.Value.Value.Key(toProto(k)))
        }
        .leftMap(err => Error.CLValueError(err))

    case CLType.URef =>
      v.to[URef]
        .map { u =>
          val key = Key.URef(u)
          state.Value(state.Value.Value.Key(toProto(key)))
        }
        .leftMap(err => Error.CLValueError(err))

    case CLType.Unit =>
      Right(state.Value(state.Value.Value.Unit(state.Unit())))

    case CLType.I64 | CLType.U64 =>
      FromBytes
        .deserialize[Long](v.value.toArray)
        .map { i =>
          state.Value(state.Value.Value.LongValue(i))
        }
        .leftMap(err => Error.FromBytesError(err))

    case CLType.Bool            => Left(Error.NoRepresentation("CLType.Bool", "state.Value"))
    case CLType.U8              => Left(Error.NoRepresentation("CLType.U8", "state.Value"))
    case CLType.Option(_)       => Left(Error.NoRepresentation("CLType.Option", "state.Value"))
    case CLType.List(t)         => Left(Error.NoRepresentation(s"CLType.List($t)", "state.Value"))
    case CLType.FixedList(_, _) => Left(Error.NoRepresentation("CLType.FixedList", "state.Value"))
    case CLType.Result(_, _)    => Left(Error.NoRepresentation("CLType.Result", "state.Value"))
    case CLType.Map(_, _)       => Left(Error.NoRepresentation("CLType.Map", "state.Value"))
    case CLType.Tuple1(_)       => Left(Error.NoRepresentation("CLType.Tuple1", "state.Value"))
    case CLType.Tuple2(t1, t2) =>
      Left(Error.NoRepresentation(s"CLType.Tuple2($t1, $t2)", "state.Value"))
    case CLType.Tuple3(_, _, _) => Left(Error.NoRepresentation("CLType.Tuple3", "state.Value"))
    case CLType.Any             => Left(Error.NoRepresentation("CLType.Any", "state.Value"))
  }

  sealed trait Error
  object Error {
    case class NoRepresentation(source: String, target: String) extends Error {
      override def toString: String = s"No representation for $source as $target"
    }

    case class FromBytesError(err: FromBytes.Error) extends Error

    case class CLValueError(err: CLValue.Error) extends Error
  }
}
