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
import io.casperlabs.models.cltype.protobuf.dsl
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
    Try(parser.fromJson[LegacyArg](js)).flatMap(legacy => Try(legacyArgToArg(legacy)))

  private def legacyArgToArg(legacy: LegacyArg): Arg =
    Arg(name = legacy.name, value = legacy.value.map(legacyValueToInstance))

  private def legacyValueToInstance(value: LegacyArg.Value): state.CLValueInstance =
    value.value match {
      case LegacyArg.Value.Value.Empty =>
        state.CLValueInstance(
          clType = state.CLType(state.CLType.Variants.Empty).some,
          value = None
        )

      case LegacyArg.Value.Value.IntValue(i) => dsl.instances.i32(i)

      case LegacyArg.Value.Value.IntList(list) =>
        state.CLValueInstance(
          clType = dsl.types.list(dsl.types.i32).some,
          value = dsl.values.list(list.values.map(dsl.values.i32)).some
        )

      case LegacyArg.Value.Value.StringValue(s) => dsl.instances.string(s)

      case LegacyArg.Value.Value.StringList(list) =>
        state.CLValueInstance(
          clType = dsl.types.list(dsl.types.string).some,
          value = dsl.values.list(list.values.map(dsl.values.string)).some
        )

      case LegacyArg.Value.Value.LongValue(i) => dsl.instances.i64(i)

      case LegacyArg.Value.Value.Key(k) => dsl.instances.key(k)

      case LegacyArg.Value.Value.BytesValue(bytes) =>
        state.CLValueInstance(
          clType = dsl.types.fixedList(dsl.types.u8, bytes.size).some,
          value = dsl.values.fixedList(bytes.toByteArray.map(dsl.values.u8)).some
        )

      case LegacyArg.Value.Value.BigInt(x) =>
        x.bitWidth match {
          case 128 => dsl.instances.u128(BigInt(x.value))
          case 256 => dsl.instances.u256(BigInt(x.value))
          case 512 => dsl.instances.u512(BigInt(x.value))

          case other =>
            throw new IllegalArgumentException(s"Invalid Legacy BigInt bit width $other")
        }

      case LegacyArg.Value.Value.OptionalValue(x) =>
        x.value match {
          case LegacyArg.Value.Value.Empty =>
            state.CLValueInstance(
              clType = dsl.types.option(dsl.types.any).some,
              value = dsl.values.option(None).some
            )

          case _ =>
            // TODO: stack safety
            val inner = legacyValueToInstance(x)
            state.CLValueInstance(
              clType = inner.clType.map(dsl.types.option),
              value = dsl.values.option(inner.value).some
            )
        }
    }
}
