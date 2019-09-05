package io.casperlabs.client.configuration

import cats._
import cats.implicits._
import cats.syntax.either._
import com.google.protobuf.wrappers
import com.google.protobuf.ByteString
import com.google.protobuf.descriptor.FieldDescriptorProto
import io.casperlabs.casper.consensus.Deploy.Arg
import io.casperlabs.crypto.codec.Base16
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
               .map { js =>
                 Try(parser.fromJson[Arg](js)).toEither.leftMap(_.getMessage)
               }
               .toList
               .sequence
    } yield args

}
