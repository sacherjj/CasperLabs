package io.casperlabs.node.configuration
import java.nio.file.Path

import scala.io.Source
import cats.syntax.either._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.configuration.SubConfig
import shapeless.<:!<
import shapeless.tag.@@

import scala.util.matching.Regex

private[configuration] object Utils {
  type NotPath[A]      = A <:!< Path
  type NotNode[A]      = A <:!< Node
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

  def readFile(source: => Source): Either[String, String] =
    try {
      source.mkString.asRight[String]
    } catch {
      case e: Throwable => e.getMessage.asLeft[String]
    }

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
}
