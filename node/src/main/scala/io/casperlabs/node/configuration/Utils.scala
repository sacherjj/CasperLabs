package io.casperlabs.node.configuration
import java.nio.file.Path

import scala.io.Source
import cats.syntax.either._

import scala.util.matching.Regex

private[configuration] object Utils {
  def readFile(source: => Source): Either[String, String] =
    try {
      source.mkString.asRight[String]
    } catch {
      case e: Throwable => e.getMessage.asLeft[String]
    }

  def dashToCamel(s: String): String =
    s.foldLeft(("", false)) {
        case ((acc, _), '-')   => (acc, true)
        case ((acc, true), c)  => (acc + c.toUpper, false)
        case ((acc, false), c) => (acc + c, false)
      }
      ._1

  def snakify(s: String): String =
    s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
      .toUpperCase

  def replacePrefix(path: Path, toRemove: Path, newPrefix: Path): String =
    path.toAbsolutePath.toString.replaceFirst(
      Regex.quoteReplacement(toRemove.toAbsolutePath.toString),
      newPrefix.toAbsolutePath.toString
    )

  def stripPrefix(path: Path, prefix: Path): String =
    path.toAbsolutePath.toString.stripPrefix(prefix.toAbsolutePath.toString)
}
