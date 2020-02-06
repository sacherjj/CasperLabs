package io.casperlabs.smartcontracts.cltype

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.smartcontracts.bytesrepr
import io.casperlabs.smartcontracts.bytesrepr.{FromBytes, ToBytes}
import scala.util.{Failure, Success, Try}

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

  def toProto(rights: Option[AccessRights]): state.Key.URef.AccessRights = rights match {
    case None                            => state.Key.URef.AccessRights.UNKNOWN
    case Some(AccessRights.Read)         => state.Key.URef.AccessRights.READ
    case Some(AccessRights.Write)        => state.Key.URef.AccessRights.WRITE
    case Some(AccessRights.ReadWrite)    => state.Key.URef.AccessRights.READ_WRITE
    case Some(AccessRights.Add)          => state.Key.URef.AccessRights.ADD
    case Some(AccessRights.ReadAdd)      => state.Key.URef.AccessRights.READ_ADD
    case Some(AccessRights.AddWrite)     => state.Key.URef.AccessRights.ADD_WRITE
    case Some(AccessRights.ReadAddWrite) => state.Key.URef.AccessRights.READ_ADD_WRITE
  }

  def toProto(uref: URef): state.Key.URef = state.Key.URef(
    uref = ByteString.copyFrom(uref.address.bytes.toArray),
    accessRights = toProto(uref.accessRights)
  )

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
        state.Key.Value.Uref(toProto(uref))
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

  def fromProto(rights: state.Key.URef.AccessRights): Either[Error, Option[AccessRights]] =
    rights match {
      case state.Key.URef.AccessRights.UNKNOWN         => Right(None)
      case state.Key.URef.AccessRights.READ            => Right(Some(AccessRights.Read))
      case state.Key.URef.AccessRights.WRITE           => Right(Some(AccessRights.Write))
      case state.Key.URef.AccessRights.READ_WRITE      => Right(Some(AccessRights.ReadWrite))
      case state.Key.URef.AccessRights.ADD             => Right(Some(AccessRights.Add))
      case state.Key.URef.AccessRights.READ_ADD        => Right(Some(AccessRights.ReadAdd))
      case state.Key.URef.AccessRights.ADD_WRITE       => Right(Some(AccessRights.AddWrite))
      case state.Key.URef.AccessRights.READ_ADD_WRITE  => Right(Some(AccessRights.ReadAddWrite))
      case state.Key.URef.AccessRights.Unrecognized(i) => Left(Error.UnrecognizedAccessRights(i))
    }

  def fromProto(uref: state.Key.URef): Either[Error, URef] =
    for {
      address <- toByteArray32(uref.uref)
      rights  <- fromProto(uref.accessRights)
    } yield URef(address, rights)

  def fromProto(k: state.Key): Either[Error, Key] = k.value match {
    case state.Key.Value.Empty => Left(Error.EmptyKeyVariant)

    case state.Key.Value.Address(state.Key.Address(address)) =>
      toByteArray32(address).map(Key.Account.apply)

    case state.Key.Value.Hash(state.Key.Hash(address)) =>
      toByteArray32(address).map(Key.Hash.apply)

    case state.Key.Value.Uref(uref) => fromProto(uref).map(Key.URef.apply)

    case state.Key.Value.Local(state.Key.Local(address)) =>
      toByteArray32(address).map(Key.Local.apply)
  }

  private def fromArg(arg: Deploy.Arg.Value): Either[Error, CLValue] = arg.value match {
    case Deploy.Arg.Value.Value.Empty          => Left(Error.EmptyArgValueVariant)
    case Deploy.Arg.Value.Value.IntValue(x)    => Right(CLValue.from(x))
    case Deploy.Arg.Value.Value.IntList(x)     => Right(CLValue.from(x.values))
    case Deploy.Arg.Value.Value.StringValue(x) => Right(CLValue.from(x))
    case Deploy.Arg.Value.Value.StringList(x)  => Right(CLValue.from(x.values))
    case Deploy.Arg.Value.Value.LongValue(x)   => Right(CLValue.from(x))
    case Deploy.Arg.Value.Value.Key(x)         => fromProto(x).map(CLValue.from[Key])

    // TODO: It is a problem with the clients that they send public keys
    // as byte arrays, but do not say they are supposed to be fixed length.
    // For now we will assume all byte arrays are fixed length and fix this when
    // we expand the `Arg` value model.
    case Deploy.Arg.Value.Value.BytesValue(x) =>
      val bytes  = x.toByteArray
      val clType = CLType.FixedList(CLType.U8, bytes.length)
      val value  = CLValue(clType, bytes)
      Right(value)

    case Deploy.Arg.Value.Value.BigInt(x) =>
      Try(BigInt(x.value)) match {
        case Failure(_) => Left(Error.InvalidBigIntValue(x.value))
        case Success(value) =>
          x.bitWidth match {
            case 128   => Right(CLValue(CLType.U128, ToBytes[BigInt].toBytes(value)))
            case 256   => Right(CLValue(CLType.U256, ToBytes[BigInt].toBytes(value)))
            case 512   => Right(CLValue(CLType.U512, ToBytes[BigInt].toBytes(value)))
            case other => Left(Error.InvalidBitWidth(other))
          }
      }

    case Deploy.Arg.Value.Value.OptionalValue(x) =>
      fromArg(x) match {
        case Left(Error.EmptyArgValueVariant) => Right(option(None))
        case Right(value)                     => Right(option(value.some))
        case otherError                       => otherError
      }
  }

  def fromArg(arg: Deploy.Arg): Either[Error, CLValue] = arg.value match {
    case None        => Left(Error.MissingArg)
    case Some(value) => fromArg(value)
  }

  private def option(inner: Option[CLValue]): CLValue = inner match {
    // TODO: the type here could be a problem; if we get a `None` we know
    // that we should return a `CLType.Option`, but we do not know what
    // the inner type should be. I am choosing `Any` for now, but
    // maybe we will need to revisit this decision in the future.
    case None => CLValue(CLType.Option(CLType.Any), Vector(bytesrepr.Constants.Option.NONE_TAG))

    case Some(value) =>
      CLValue(CLType.Option(value.clType), bytesrepr.Constants.Option.SOME_TAG +: value.value)
  }

  private def toByteArray32(bytes: ByteString): Either[Error, ByteArray32] =
    ByteArray32(bytes.toByteArray) match {
      case None          => Left(Error.Expected32Bytes(found = bytes.toByteArray.toIndexedSeq))
      case Some(bytes32) => Right(bytes32)
    }

  sealed trait Error
  object Error {
    case class NoRepresentation(source: String, target: String) extends Error {
      override def toString: String = s"No representation for $source as $target"
    }

    case class FromBytesError(err: FromBytes.Error) extends Error

    case class CLValueError(err: CLValue.Error) extends Error

    case class Expected32Bytes(found: IndexedSeq[Byte]) extends Error

    case object MissingArg           extends Error
    case object EmptyKeyVariant      extends Error
    case object EmptyArgValueVariant extends Error

    case class InvalidBigIntValue(value: String) extends Error
    case class InvalidBitWidth(bitWidth: Int)    extends Error

    case class UnrecognizedAccessRights(enumValue: Int) extends Error
  }
}
