package io.casperlabs.client.configuration

import cats._
import cats.implicits._
import cats.syntax.either._
import com.google.protobuf.wrappers
import com.google.protobuf.ByteString
import com.google.protobuf.descriptor.FieldDescriptorProto
import io.casperlabs.casper.consensus.Deploy.{Arg, LegacyArg}
import io.casperlabs.casper.consensus.state
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.smartcontracts.cltype.ProtoConstructor
import io.circe.Json
import org.rogach.scallop._
import scalapb_circe.{JsonFormat, Parser, Printer}
import scalapb_json.JsonFormatException
import scalapb.GeneratedMessageCompanion
import scalapb.descriptors.{FieldDescriptor, PByteString, PMessage, PValue, ScalaType}
import scala.util.Try
import com.google.protobuf.descriptor.FieldDescriptorProto.Type.TYPE_BYTES

case class Args(args: Seq[Arg])

object Args {
  // Convert from JSON string to args.
  implicit val valueConverter: ValueConverter[Args] =
    implicitly[ValueConverter[String]].flatMap { json =>
      Args.fromJson(json).map(args => Some(Args(args)))
    }

  // Override the format of `bytes` to be Base16.
  // https://github.com/scalapb-json/scalapb-circe/blob/0.2.x/shared/src/main/scala/scalapb_circe/JsonFormat.scala
  val parser = new Parser(
    preservingProtoFieldNames = true
  ) {
    override def parseSingleValue(
        containerCompanion: GeneratedMessageCompanion[_],
        fd: FieldDescriptor,
        value: Json
    ): PValue =
      fd.scalaType match {
        case ScalaType.ByteString =>
          value.asString match {
            case Some(s) =>
              PByteString(ByteString.copyFrom(Base16.decode(s)))
            case None =>
              throw new JsonFormatException(
                s"Unexpected value ($value) for field ${fd.asProto.getName} of ${fd.containingMessage.name}"
              )
          }
        case _ =>
          super.parseSingleValue(containerCompanion, fd, value)
      }
  }

  // Parse JSON formatted arguments using the Proto3 JSON format.
  def fromJson(s: String): Either[String, List[Arg]] =
    for {
      json <- io.circe.parser.parse(s).leftMap(_.message)
      arr  <- json.as[List[Json]].leftMap(_.message)
      args <- arr
               .traverse { js =>
                 Try(parser.fromJson[Arg](js))
                 // Fall back on the old JSON format if we could not parse a type
                 // TODO: remove this when there are no more usages of the old format
                   .flatMap { arg =>
                     if (arg.value.flatMap(_.clType).isEmpty) legacyFromJson(js)
                     else Try(arg)
                   }
                   .toEither
                   .leftMap(_.getMessage)
               }
    } yield args

  private def legacyFromJson(js: Json): Try[Arg] =
    Try(parser.fromJson[LegacyArg](js)).map(legacyArgToArg)

  private def legacyArgToArg(legacy: LegacyArg): Arg =
    Arg(name = legacy.name, value = legacy.value.map(legacyValueToInstance))

  private def legacyValueToInstance(value: LegacyArg.Value): state.CLValueInstance =
    value.value match {
      case LegacyArg.Value.Value.Empty =>
        state.CLValueInstance(
          clType = state.CLType(state.CLType.Variants.Empty).some,
          value = None
        )

      case LegacyArg.Value.Value.IntValue(i) => i32Value(i)

      case LegacyArg.Value.Value.IntList(list) =>
        state.CLValueInstance(
          clType = listType(i32Type).some,
          value = ProtoConstructor.list(list.values.map(ProtoConstructor.i32)).some
        )

      case LegacyArg.Value.Value.StringValue(s) => stringValue(s)

      case LegacyArg.Value.Value.StringList(list) =>
        state.CLValueInstance(
          clType = listType(stringType).some,
          value = ProtoConstructor.list(list.values.map(ProtoConstructor.string)).some
        )

      case LegacyArg.Value.Value.LongValue(i) =>
        state.CLValueInstance(
          clType = i64Type.some,
          value = ProtoConstructor.i64(i).some
        )

      case LegacyArg.Value.Value.Key(k) =>
        state.CLValueInstance(
          clType = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.KEY)).some,
          value = ProtoConstructor.key(k).some
        )

      case LegacyArg.Value.Value.BytesValue(bytes) =>
        state.CLValueInstance(
          clType = fixedListType(u8Type, bytes.size).some,
          value = ProtoConstructor.fixedList(bytes.toByteArray.map(ProtoConstructor.u8)).some
        )

      case LegacyArg.Value.Value.BigInt(x) =>
        x.bitWidth match {
          case 128 => u128Value(x.value)
          case 256 => u256Value(x.value)
          case 512 => u512Value(x.value)

          case other =>
            throw new IllegalArgumentException(s"Invalid Legacy BigInt bit width $other")
        }

      case LegacyArg.Value.Value.OptionalValue(x) =>
        x.value match {
          case LegacyArg.Value.Value.Empty =>
            state.CLValueInstance(
              clType = optionType(anyType).some,
              value = ProtoConstructor.option(None).some
            )

          case _ =>
            // TODO: stack safety
            val inner = legacyValueToInstance(x)
            state.CLValueInstance(
              clType = inner.clType.map(optionType),
              value = ProtoConstructor.option(inner.value).some
            )
        }
    }

  private val u8Type   = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U8))
  private val i32Type  = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I32))
  private val i64Type  = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.I64))
  private val u128Type = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U128))
  private val u256Type = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U256))
  private val u512Type = state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.U512))

  private val stringType =
    state.CLType(state.CLType.Variants.SimpleType(state.CLType.Simple.STRING))

  private val anyType = state.CLType(state.CLType.Variants.AnyType(state.CLType.Any()))

  private def optionType(inner: state.CLType) =
    state.CLType(state.CLType.Variants.OptionType(state.CLType.OptionProto(inner.some)))

  private def listType(inner: state.CLType) =
    state.CLType(state.CLType.Variants.ListType(state.CLType.List(inner.some)))

  private def fixedListType(inner: state.CLType, length: Int) = state.CLType(
    state.CLType.Variants.FixedListType(state.CLType.FixedList(inner.some, length))
  )

  private def i32Value(i: Int) = state.CLValueInstance(
    clType = i32Type.some,
    value = ProtoConstructor.i32(i).some
  )

  private def u128Value(value: String) = state.CLValueInstance(
    clType = u128Type.some,
    value = state.CLValueInstance
      .Value(
        state.CLValueInstance.Value.Value.U128(
          state.CLValueInstance.U128(value)
        )
      )
      .some
  )

  private def u256Value(value: String) = state.CLValueInstance(
    clType = u256Type.some,
    value = state.CLValueInstance
      .Value(
        state.CLValueInstance.Value.Value.U256(
          state.CLValueInstance.U256(value)
        )
      )
      .some
  )

  private def u512Value(value: String) = state.CLValueInstance(
    clType = u512Type.some,
    value = state.CLValueInstance
      .Value(
        state.CLValueInstance.Value.Value.U512(
          state.CLValueInstance.U512(value)
        )
      )
      .some
  )

  private def stringValue(s: String) = state.CLValueInstance(
    clType = stringType.some,
    value = ProtoConstructor.string(s).some
  )
}
