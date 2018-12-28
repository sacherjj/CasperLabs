package io.casperlabs.node.configuration.updated
import java.io.File
import java.nio.file.{Files, Path, Paths}

import io.casperlabs.comm.PeerNode
import simulacrum.typeclass
import cats.syntax.either._
import toml._
import toml.Codecs._
import toml.Toml._
import cats.syntax.either._
import io.casperlabs.shared.StoreType

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.io.Source
import scala.util.Try
import scala.collection.JavaConverters._

@typeclass trait TomlReader[F] {
  def default: Either[String, F]
  def from(raw: String): Either[String, F]
  def from(path: Path): Either[String, F] =
    Try(Files.readAllLines(path).asScala.mkString("")).toEither
      .leftMap(_.getMessage)
      .flatMap(raw => from(raw))
}

object TomlReader {
  object Implicits {
    private implicit val bootstrapAddressCodec: Codec[PeerNode] =
      Codec {
        case (Value.Str(uri), _) =>
          PeerNode
            .fromAddress(uri)
            .map(u => Right(u))
            .getOrElse(Left((Nil, "can't parse the rnode bootstrap address")))
        case _ => Left((Nil, "the rnode bootstrap address should be a string"))
      }
    private implicit val pathCodec: Codec[Path] =
      Codec {
        case (Value.Str(uri), _) =>
          Try(Paths.get(uri)).toEither.leftMap(_ => (Nil, s"Can't parse the path $uri"))
        case _ => Left((Nil, "A path must be a string"))
      }
    private implicit val boolCodec: Codec[Boolean] = Codec {
      case (Value.Bool(value), _) => Right(value)
      case (value, _) =>
        Left((List.empty, s"Bool expected, $value provided"))
    }
    private implicit val finiteDurationCodec: Codec[FiniteDuration] = Codec {
      case (Value.Str(value), _) =>
        Duration(value) match {
          case fd: FiniteDuration => Either.right(fd)
          case _                  => Either.left((Nil, s"Failed to parse $value as FiniteDuration."))
        }
      case (value, _) =>
        Either.left((Nil, s"Failed to parse $value as FiniteDuration."))
    }
    private implicit val storeTypeCodec: Codec[StoreType] = Codec {
      case (Value.Str(value), _) =>
        StoreType
          .from(value)
          .map(Right(_))
          .getOrElse(Left(Nil, s"Failed to parse $value as StoreType"))
      case (value, _) =>
        Left(Nil, s"Failed to parse $value as StoreType")
    }

    implicit val runCommandToml = new TomlReader[Configuration.Run] {
      override def default: Either[String, Configuration.Run] =
        Try(Source.fromResource("default-configuration.toml").mkString).toEither
          .leftMap(_.getMessage)
          .flatMap(parseRun)

      override def from(raw: String): Either[String, Configuration.Run] =
        parseRun(raw)
    }

    implicit val diagnosticsToml = new TomlReader[Configuration.Diagnostics] {
      override def default: Either[String, Configuration.Diagnostics] =
        Try(Source.fromResource("default-configuration.toml").mkString).toEither
          .leftMap(_.getMessage)
          .flatMap(parseDiagnostics)

      override def from(raw: String): Either[String, Configuration.Diagnostics] =
        parseDiagnostics(raw)
    }

    private def parseRun(raw: String): Either[String, Configuration.Run] =
      Toml
        .parse(raw)
        .map(rewriteKeysToCamelCase)
        .flatMap(
          table =>
            table.values("run") match {
              case newTable: Value.Tbl => Right(newTable)
              case _                   => Left(s"Couldn't parse $table as Configuration.Diagnostics")
            }
        )
        .flatMap(
          table => {
            Toml
              .parseAs[Configuration.Run](table)
              .leftMap {
                case (address, message) =>
                  s"$message at $address"
              }
          }
        )

    private def parseDiagnostics(raw: String): Either[String, Configuration.Diagnostics] =
      Toml
        .parse(raw)
        .map(rewriteKeysToCamelCase)
        .flatMap(
          table =>
            table.values("diagnostics") match {
              case newTable: Value.Tbl => Right(newTable)
              case _                   => Left(s"Couldn't parse $table as Configuration.Diagnostics")
            }
        )
        .flatMap(
          table => {
            Toml
              .parseAs[Configuration.Diagnostics](table)
              .leftMap {
                case (address, message) =>
                  s"$message at $address"
              }
          }
        )

    private def rewriteKeysToCamelCase(tbl: Value.Tbl): Value.Tbl = {
      def rewriteTbl(t: Value.Tbl): Value.Tbl =
        Value.Tbl(
          t.values.map {
            case (key, t1 @ Value.Tbl(_)) => (camelify(key), rewriteTbl(t1))
            case (key, a @ Value.Arr(_))  => (camelify(key), rewriteArr(a))
            case (key, value)             => (camelify(key), value)
          }
        )

      def rewriteArr(a: Value.Arr): Value.Arr =
        Value.Arr(
          a.values.map {
            case t1 @ Value.Tbl(_) => rewriteTbl(t1)
            case a @ Value.Arr(_)  => rewriteArr(a)
            case value             => value
          }
        )

      rewriteTbl(tbl)
    }

    private def camelify(name: String): String = {
      def loop(x: List[Char]): List[Char] = (x: @unchecked) match {
        case '-' :: '-' :: rest => loop('_' :: rest)
        case '-' :: c :: rest   => Character.toUpperCase(c) :: loop(rest)
        case '-' :: Nil         => Nil
        case c :: rest          => c :: loop(rest)
        case Nil                => Nil
      }

      loop(name.toList).mkString
    }
  }
}
