package io.casperlabs.smartcontracts.cltype

import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.smartcontracts.bytesrepr
import io.casperlabs.smartcontracts.bytesrepr.{FromBytes, ToBytes}
import scala.util.{Failure, Success, Try}

object ProtoMappings {
  def toProto(v: StoredValue): Either[Error, state.StoredValueInstance] = v match {
    case StoredValue.CLValue(value) =>
      toProto(value).map { instance =>
        state.StoredValueInstance(state.StoredValueInstance.Value.ClValue(instance))
      }

    case StoredValue.Account(account) =>
      Right(state.StoredValueInstance(state.StoredValueInstance.Value.Account(toProto(account))))

    case StoredValue.Contract(contract) =>
      Right(state.StoredValueInstance(state.StoredValueInstance.Value.Contract(toProto(contract))))
  }

  def toProto(c: Contract): state.Contract = state.Contract(
    body = ByteString.copyFrom(c.bytes.toArray),
    namedKeys = toProto(c.namedKeys),
    protocolVersion = Some(toProto(c.protocolVersion))
  )

  def toProto(a: Account): state.Account = state.Account(
    publicKey = ByteString.copyFrom(a.publicKey.bytes.toArray),
    mainPurse = toProto(Key.URef(a.mainPurse)).value.uref,
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

    case Key.Local(seed, hash) =>
      val address: Array[Byte] = (seed.bytes ++ hash.bytes).toArray
      state.Key(
        state.Key.Value
          .Local(state.Key.Local(ByteString.copyFrom(address)))
      )
  }

  def toProto(version: SemVer): state.ProtocolVersion = state.ProtocolVersion(
    major = version.major,
    minor = version.minor,
    patch = version.patch
  )

  def toProto(v: CLValue): Either[Error, state.CLValueInstance] =
    CLValueInstance.from(v).map(toProto).leftMap(Error.FromBytesError.apply)

  def toProto(t: CLType): state.CLType = t match {
    case CLType.Bool   => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.BOOL))
    case CLType.I32    => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I32))
    case CLType.I64    => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I64))
    case CLType.U8     => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U8))
    case CLType.U32    => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U32))
    case CLType.U64    => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U64))
    case CLType.U128   => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U128))
    case CLType.U256   => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U256))
    case CLType.U512   => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U512))
    case CLType.Unit   => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UNIT))
    case CLType.String => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.STRING))
    case CLType.Key    => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.KEY))
    case CLType.URef   => state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UREF))

    case CLType.Option(inner) =>
      //TODO: stack safety
      val innerProto = toProto(inner)
      state.CLType(state.CLType.Variants.OptionType(state.CLType.OptionProto(innerProto.some)))

    case CLType.List(inner) =>
      val innerProto = toProto(inner)
      state.CLType(state.CLType.Variants.ListType(state.CLType.List(innerProto.some)))

    case CLType.FixedList(inner, length) =>
      val innerProto = toProto(inner)
      state.CLType(
        state.CLType.Variants.FixedListType(state.CLType.FixedList(innerProto.some, length))
      )

    case CLType.Result(ok, err) =>
      val okProto  = toProto(ok)
      val errProto = toProto(err)
      state.CLType(
        state.CLType.Variants
          .ResultType(state.CLType.Result(ok = okProto.some, err = errProto.some))
      )

    case CLType.Map(key, value) =>
      val keyProto   = toProto(key)
      val valueProto = toProto(value)
      state.CLType(
        state.CLType.Variants
          .MapType(state.CLType.Map(key = keyProto.some, value = valueProto.some))
      )

    case CLType.Tuple1(inner) =>
      val innerProto = toProto(inner)
      state.CLType(state.CLType.Variants.Tuple1Type(state.CLType.Tuple1(innerProto.some)))

    case CLType.Tuple2(t1, t2) =>
      val t1Proto = toProto(t1)
      val t2Proto = toProto(t2)
      state.CLType(
        state.CLType.Variants
          .Tuple2Type(state.CLType.Tuple2(t1Proto.some, t2Proto.some))
      )

    case CLType.Tuple3(t1, t2, t3) =>
      val t1Proto = toProto(t1)
      val t2Proto = toProto(t2)
      val t3Proto = toProto(t3)
      state.CLType(
        state.CLType.Variants
          .Tuple3Type(state.CLType.Tuple3(t1Proto.some, t2Proto.some, t3Proto.some))
      )

    case CLType.Any => state.CLType(state.CLType.Variants.AnyType(state.CLType.Any()))
  }

  def toProto(v: CLValueInstance): state.CLValueInstance = v match {
    case CLValueInstance.Bool(b) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.BOOL)).some,
        value = ProtoConstructor.bool(b).some
      )

    case CLValueInstance.I32(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I32)).some,
        value = ProtoConstructor.i32(i).some
      )

    case CLValueInstance.I64(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I64)).some,
        value = ProtoConstructor.i64(i).some
      )

    case CLValueInstance.U8(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U8)).some,
        value = ProtoConstructor.u8(i).some
      )

    case CLValueInstance.U32(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U32)).some,
        value = ProtoConstructor.u32(i).some
      )

    case CLValueInstance.U64(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U64)).some,
        value = ProtoConstructor.u64(i).some
      )

    case CLValueInstance.U128(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U128)).some,
        value = ProtoConstructor.u128(i.value).some
      )

    case CLValueInstance.U256(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U256)).some,
        value = ProtoConstructor.u256(i.value).some
      )

    case CLValueInstance.U512(i) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U512)).some,
        value = ProtoConstructor.u512(i.value).some
      )

    case CLValueInstance.Unit =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UNIT)).some,
        value = ProtoConstructor.unit.some
      )

    case CLValueInstance.String(s) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.STRING)).some,
        value = ProtoConstructor.string(s).some
      )

    case CLValueInstance.Key(k) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.KEY)).some,
        value = ProtoConstructor.key(toProto(k)).some
      )

    case CLValueInstance.URef(u) =>
      state.CLValueInstance(
        clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UREF)).some,
        value = ProtoConstructor.uref(toProto(u)).some
      )

    case option @ CLValueInstance.Option(value, _) =>
      val clType = toProto(option.clType)
      // TODO: stack safety
      val innerProto = value.flatMap(v => toProto(v).value)
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.option(innerProto).some
      )

    case list @ CLValueInstance.List(values, _) =>
      val clType     = toProto(list.clType)
      val innerProto = values.flatMap(v => toProto(v).value)
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.list(innerProto).some
      )

    case list @ CLValueInstance.FixedList(values, _, _) =>
      val clType     = toProto(list.clType)
      val innerProto = values.flatMap(v => toProto(v).value)
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.fixedList(innerProto).some
      )

    case result @ CLValueInstance.Result(value, _, _) =>
      val clType     = toProto(result.clType)
      val innerProto = value.bimap(v => toProto(v).value.get, v => toProto(v).value.get)
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.result(innerProto).some
      )

    case map @ CLValueInstance.Map(values, _, _) =>
      val clType = toProto(map.clType)
      val innerProto = values.toList.map {
        case (key, value) =>
          state.CLValueInstance.MapEntry(key = toProto(key).value, value = toProto(value).value)
      }
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.map(innerProto).some
      )

    case tuple1 @ CLValueInstance.Tuple1(value) =>
      val clType     = toProto(tuple1.clType)
      val innerProto = toProto(value).value.get
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.tuple1(innerProto).some
      )

    case tuple2 @ CLValueInstance.Tuple2(value1, value2) =>
      val clType      = toProto(tuple2.clType)
      val innerProto1 = toProto(value1).value.get
      val innerProto2 = toProto(value2).value.get
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.tuple2(innerProto1, innerProto2).some
      )

    case tuple3 @ CLValueInstance.Tuple3(value1, value2, value3) =>
      val clType      = toProto(tuple3.clType)
      val innerProto1 = toProto(value1).value.get
      val innerProto2 = toProto(value2).value.get
      val innerProto3 = toProto(value3).value.get
      state.CLValueInstance(
        clType = clType.some,
        value = ProtoConstructor.tuple3(innerProto1, innerProto2, innerProto3).some
      )
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
      if (address.size != 64) Left(Error.Expected64Bytes(address.size))
      else {
        val seedBytes = address.substring(0, 32)
        val hashBytes = address.substring(32, 64)
        for {
          seed <- toByteArray32(seedBytes)
          hash <- toByteArray32(hashBytes)
        } yield Key.Local(seed, hash)
      }
  }

  def fromProto(proto: state.CLType): Either[Error, CLType] = proto match {
    case state.CLType(state.CLType.Variants.Empty) => Left(Error.EmptyTypeVariant)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.Unrecognized(i))) =>
      Left(Error.UnrecognizedSimpleType(i))

    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.BOOL)) =>
      Right(CLType.Bool)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I32)) =>
      Right(CLType.I32)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I64)) =>
      Right(CLType.I64)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U8)) => Right(CLType.U8)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U32)) =>
      Right(CLType.U32)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U64)) =>
      Right(CLType.U64)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U128)) =>
      Right(CLType.U128)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U256)) =>
      Right(CLType.U256)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U512)) =>
      Right(CLType.U512)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UNIT)) =>
      Right(CLType.Unit)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.STRING)) =>
      Right(CLType.String)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.KEY)) =>
      Right(CLType.Key)
    case state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.UREF)) =>
      Right(CLType.URef)

    // TODO: stack safety
    case state.CLType(state.CLType.Variants.OptionType(state.CLType.OptionProto(innerProto))) =>
      val inner = innerProto.map(fromProto).getOrElse(Left(Error.MissingType))
      inner.map(CLType.Option.apply)

    case state.CLType(state.CLType.Variants.ListType(state.CLType.List(innerProto))) =>
      val inner = innerProto.map(fromProto).getOrElse(Left(Error.MissingType))
      inner.map(CLType.List.apply)

    case state.CLType(
        state.CLType.Variants.FixedListType(state.CLType.FixedList(innerProto, length))
        ) =>
      val inner = innerProto.map(fromProto).getOrElse(Left(Error.MissingType))
      inner.map(CLType.FixedList(_, length))

    case state.CLType(state.CLType.Variants.ResultType(state.CLType.Result(okProto, errProto))) =>
      for {
        ok  <- okProto.map(fromProto).getOrElse(Left(Error.MissingType))
        err <- errProto.map(fromProto).getOrElse(Left(Error.MissingType))
      } yield CLType.Result(ok, err)

    case state.CLType(state.CLType.Variants.MapType(state.CLType.Map(keyProto, valueProto))) =>
      for {
        key   <- keyProto.map(fromProto).getOrElse(Left(Error.MissingType))
        value <- valueProto.map(fromProto).getOrElse(Left(Error.MissingType))
      } yield CLType.Map(key, value)

    case state.CLType(state.CLType.Variants.Tuple1Type(state.CLType.Tuple1(innerProto))) =>
      val inner = innerProto.map(fromProto).getOrElse(Left(Error.MissingType))
      inner.map(CLType.Tuple1.apply)

    case state.CLType(state.CLType.Variants.Tuple2Type(state.CLType.Tuple2(t1Proto, t2Proto))) =>
      for {
        t1 <- t1Proto.map(fromProto).getOrElse(Left(Error.MissingType))
        t2 <- t2Proto.map(fromProto).getOrElse(Left(Error.MissingType))
      } yield CLType.Tuple2(t1, t2)

    case state.CLType(
        state.CLType.Variants.Tuple3Type(state.CLType.Tuple3(t1Proto, t2Proto, t3Proto))
        ) =>
      for {
        t1 <- t1Proto.map(fromProto).getOrElse(Left(Error.MissingType))
        t2 <- t2Proto.map(fromProto).getOrElse(Left(Error.MissingType))
        t3 <- t3Proto.map(fromProto).getOrElse(Left(Error.MissingType))
      } yield CLType.Tuple3(t1, t2, t3)

    case state.CLType(state.CLType.Variants.AnyType(state.CLType.Any())) => Right(CLType.Any)
  }

  def fromProto(
      value: state.CLValueInstance.Value,
      clType: CLType
  ): Either[Error, CLValueInstance] =
    value.value match {
      case state.CLValueInstance.Value.Value.Empty        => Left(Error.EmptyInstanceVariant)
      case state.CLValueInstance.Value.Value.BoolValue(b) => Right(CLValueInstance.Bool(b))
      case state.CLValueInstance.Value.Value.I32(i)       => Right(CLValueInstance.I32(i))
      case state.CLValueInstance.Value.Value.I64(i)       => Right(CLValueInstance.I64(i))
      case state.CLValueInstance.Value.Value.U8(i)        => Right(CLValueInstance.U8(i.toByte))
      case state.CLValueInstance.Value.Value.U32(i)       => Right(CLValueInstance.U32(i))
      case state.CLValueInstance.Value.Value.U64(i)       => Right(CLValueInstance.U64(i))

      case state.CLValueInstance.Value.Value.U128(i) =>
        validateBigInt(i.value).map(CLValueInstance.U128.apply)
      case state.CLValueInstance.Value.Value.U256(i) =>
        validateBigInt(i.value).map(CLValueInstance.U256.apply)
      case state.CLValueInstance.Value.Value.U512(i) =>
        validateBigInt(i.value).map(CLValueInstance.U512.apply)

      case state.CLValueInstance.Value.Value.Unit(_)     => Right(CLValueInstance.Unit)
      case state.CLValueInstance.Value.Value.StrValue(s) => Right(CLValueInstance.String(s))
      case state.CLValueInstance.Value.Value.Key(k)      => fromProto(k).map(CLValueInstance.Key.apply)
      case state.CLValueInstance.Value.Value.Uref(u)     => fromProto(u).map(CLValueInstance.URef.apply)

      // TODO: stack safety
      case state.CLValueInstance.Value.Value
            .OptionValue(state.CLValueInstance.OptionProto(innerProto)) =>
        clType match {
          case CLType.Option(innerType) =>
            innerProto.traverse(v => fromProto(v, innerType)).flatMap { innerValue =>
              CLValueInstance.Option(innerValue, innerType).leftMap(Error.InstanceError.apply)
            }

          case other => Left(Error.TypeMismatch(other, "Option"))
        }

      case state.CLValueInstance.Value.Value.ListValue(state.CLValueInstance.List(innerProto)) =>
        clType match {
          case CLType.List(innerType) =>
            innerProto.toList.traverse(v => fromProto(v, innerType)).flatMap { innerValue =>
              CLValueInstance.List(innerValue, innerType).leftMap(Error.InstanceError.apply)
            }

          case other => Left(Error.TypeMismatch(other, "List"))
        }

      case state.CLValueInstance.Value.Value
            .FixedListValue(state.CLValueInstance.FixedList(length, innerProto)) =>
        clType match {
          case CLType.FixedList(innerType, l) if l == length =>
            innerProto.toList.traverse(v => fromProto(v, innerType)).flatMap { innerValue =>
              CLValueInstance
                .FixedList(innerValue, innerType, length)
                .leftMap(Error.InstanceError.apply)
            }

          case other => Left(Error.TypeMismatch(other, s"FixedList(length == $length)"))
        }

      case state.CLValueInstance.Value.Value
            .ResultValue(state.CLValueInstance.Result(innerProto)) =>
        clType match {
          case CLType.Result(okType, errType) =>
            innerProto match {
              case state.CLValueInstance.Result.Value.Empty => Left(Error.EmptyResultVariant)

              case state.CLValueInstance.Result.Value.Err(e) =>
                fromProto(e, errType).flatMap { innerValue =>
                  CLValueInstance
                    .Result(Left(innerValue), okType, errType)
                    .leftMap(Error.InstanceError.apply)
                }

              case state.CLValueInstance.Result.Value.Ok(k) =>
                fromProto(k, okType).flatMap { innerValue =>
                  CLValueInstance
                    .Result(Right(innerValue), okType, errType)
                    .leftMap(Error.InstanceError.apply)
                }
            }

          case other => Left(Error.TypeMismatch(other, "Result"))
        }

      case state.CLValueInstance.Value.Value.MapValue(state.CLValueInstance.Map(innerProto)) =>
        clType match {
          case CLType.Map(keyType, valueType) =>
            val values = innerProto.toList.traverse {
              case state.CLValueInstance.MapEntry(keyProto, valueProto) =>
                for {
                  key <- keyProto
                          .map(k => fromProto(k, keyType))
                          .getOrElse(Left(Error.MissingInstance))
                  value <- valueProto
                            .map(v => fromProto(v, valueType))
                            .getOrElse(Left(Error.MissingInstance))
                } yield (key, value)
            }
            values.flatMap { pairs =>
              CLValueInstance
                .Map(pairs.toMap, keyType, valueType)
                .leftMap(Error.InstanceError.apply)
            }

          case other => Left(Error.TypeMismatch(other, "Map"))
        }

      case state.CLValueInstance.Value.Value
            .Tuple1Value(state.CLValueInstance.Tuple1(innerProto)) =>
        clType match {
          case CLType.Tuple1(innerType) =>
            innerProto
              .map(v => fromProto(v, innerType))
              .getOrElse(Left(Error.MissingInstance))
              .map { innerValue =>
                CLValueInstance.Tuple1(innerValue)
              }

          case other => Left(Error.TypeMismatch(other, "Tuple1"))
        }

      case state.CLValueInstance.Value.Value
            .Tuple2Value(state.CLValueInstance.Tuple2(t1Proto, t2Proto)) =>
        clType match {
          case CLType.Tuple2(type1, type2) =>
            for {
              v1 <- t1Proto.map(v => fromProto(v, type1)).getOrElse(Left(Error.MissingInstance))
              v2 <- t2Proto.map(v => fromProto(v, type2)).getOrElse(Left(Error.MissingInstance))
            } yield CLValueInstance.Tuple2(v1, v2)

          case other => Left(Error.TypeMismatch(other, "Tuple2"))
        }

      case state.CLValueInstance.Value.Value
            .Tuple3Value(state.CLValueInstance.Tuple3(t1Proto, t2Proto, t3Proto)) =>
        clType match {
          case CLType.Tuple3(type1, type2, type3) =>
            for {
              v1 <- t1Proto.map(v => fromProto(v, type1)).getOrElse(Left(Error.MissingInstance))
              v2 <- t2Proto.map(v => fromProto(v, type2)).getOrElse(Left(Error.MissingInstance))
              v3 <- t3Proto.map(v => fromProto(v, type3)).getOrElse(Left(Error.MissingInstance))
            } yield CLValueInstance.Tuple3(v1, v2, v3)

          case other => Left(Error.TypeMismatch(other, "Tuple3"))
        }
    }

  def fromProto(proto: state.CLValueInstance): Either[Error, CLValueInstance] = proto.value match {
    case None => Left(Error.MissingValue)
    case Some(value) =>
      proto.clType
        .map(fromProto)
        .getOrElse(Left(Error.MissingType))
        .flatMap(clType => fromProto(value, clType))
  }

  def fromArg(arg: Deploy.Arg): Either[Error, CLValue] = arg.value match {
    case None => Left(Error.MissingArg)
    case Some(value) =>
      for {
        instance <- fromProto(value)
        clValue  <- instance.toValue.leftMap(Error.InstanceError.apply)
      } yield clValue
  }

  private def toByteArray32(bytes: ByteString): Either[Error, ByteArray32] =
    ByteArray32(bytes.toByteArray) match {
      case None          => Left(Error.Expected32Bytes(foundLength = bytes.size))
      case Some(bytes32) => Right(bytes32)
    }

  private def validateBigInt(s: String): Either[Error, BigInt Refined NonNegative] =
    Try(BigInt(s)) match {
      case Failure(_) => Left(Error.InvalidBigIntValue(s))
      case Success(i) => refineV[NonNegative](i).leftMap(_ => Error.InvalidBigIntValue(s))
    }

  sealed trait Error
  object Error {
    case class NoRepresentation(source: String, target: String) extends Error {
      override def toString: String = s"No representation for $source as $target"
    }

    case class TypeMismatch(tagType: CLType, valueType: String) extends Error

    case class InstanceError(err: CLValueInstance.Error) extends Error

    case class FromBytesError(err: FromBytes.Error) extends Error

    case class Expected32Bytes(foundLength: Int) extends Error
    case class Expected64Bytes(foundLength: Int) extends Error

    case object MissingType          extends Error
    case object MissingInstance      extends Error
    case object MissingArg           extends Error
    case object MissingValue         extends Error
    case object EmptyKeyVariant      extends Error
    case object EmptyArgValueVariant extends Error
    case object EmptyInstanceVariant extends Error
    case object EmptyResultVariant   extends Error
    case object EmptyTypeVariant     extends Error

    case class InvalidBigIntValue(value: String) extends Error
    case class InvalidBitWidth(bitWidth: Int)    extends Error

    case class UnrecognizedAccessRights(enumValue: Int) extends Error
    case class UnrecognizedSimpleType(enumValue: Int)   extends Error
  }
}
