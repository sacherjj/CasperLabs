package io.casperlabs.node.configuration
import java.nio.file.{Files, Path}

import cats.syntax.either._
import io.casperlabs.comm.discovery.NodeUtils.NodeWithoutChainId
import io.casperlabs.configuration.SubConfig
import shapeless.<:!<
import shapeless.tag.@@

import scala.io.Source
import scala.util.Try
import scala.util.control.NonFatal
import scala.util.matching.Regex

private[configuration] object Utils {
  type NotPath[A]      = A <:!< Path
  type NotNode[A]      = A <:!< NodeWithoutChainId
  type IsSubConfig[A]  = A <:< SubConfig
  type NotSubConfig[A] = A <:!< SubConfig
  type NotOption[A]    = A <:!< Option[_]

  sealed trait CamelCaseTag
  type CamelCase = String @@ CamelCaseTag
  def CamelCase(s: String): CamelCase = s.asInstanceOf[CamelCase]

  sealed trait SnakeCaseTag
  type SnakeCase = String @@ SnakeCaseTag
  def isSnakeCase(s: String): Boolean = s.matches("[A-Z_]+")
  def SnakeCase(s: String): SnakeCase = s.asInstanceOf[SnakeCase]

  def readFile(source: => Source): Either[String, String] = {
    val src = source
    try {
      try {
        src.mkString.asRight[String]
      } catch {
        case NonFatal(e) => e.getMessage.asLeft[String]
      }
    } finally {
      src.close()
    }
  }

  def readFile(path: Path): Either[String, String] =
    readFile(Source.fromFile(path.toFile)).leftMap(err => s"Could not read '$path': $err")

  def readBytes(path: Path): Either[String, Array[Byte]] =
    Try(Files.readAllBytes(path)).toEither
      .leftMap(ex => s"Could not read '$path': ${ex.getMessage}")

  def dashToCamel(s: String): CamelCase =
    CamelCase(
      s.foldLeft(("", false)) {
          case ((acc, _), '-')   => (acc, true)
          case ((acc, true), c)  => (acc + c.toUpper, false)
          case ((acc, false), c) => (acc + c, false)
        }
        ._1
    )

  def constructCamelCase(pathToField: List[String]): CamelCase = {
    val reversed = pathToField.reverse
    CamelCase((reversed.head :: reversed.tail.map(_.capitalize)).mkString(""))
  }

  def snakify(s: String): SnakeCase =
    SnakeCase(
      s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
        .toUpperCase
    )

  def replacePrefix(path: Path, toRemove: Path, newPrefix: Path): String =
    path.toAbsolutePath.toString.replaceFirst(
      Regex.quoteReplacement(toRemove.toAbsolutePath.toString),
      newPrefix.toAbsolutePath.toString
    )

  def stripPrefix(path: Path, prefix: Path): String =
    path.toAbsolutePath.toString.stripPrefix(prefix.toAbsolutePath.toString)

  def parseToml(content: String): Map[CamelCase, String] = {
    val tableRegex = """\[(.+)\]""".r
    val valueRegex = """([a-z\-]+)\s*=\s*\"?([^\"]*)\"?""".r

    val lines = content
      .split('\n')
    val withoutCommentsAndEmptyLines = lines
      .filterNot(s => s.startsWith("#") || s.trim.isEmpty)
      .map(_.trim)

    val dashifiedMap: Map[String, String] = withoutCommentsAndEmptyLines
      .foldLeft((Map.empty[String, String], Option.empty[String])) {
        case ((acc, _), tableRegex(table)) =>
          (acc, Some(table))
        case ((acc, t @ Some(currentTable)), valueRegex(key, value)) =>
          (acc + (currentTable + "-" + key -> value), t)
        case (x, _) => x
      }
      ._1

    val camelCasedMap: Map[CamelCase, String] = dashifiedMap.map {
      case (k, v) => (dashToCamel(k), v)
    }

    camelCasedMap
  }
}
