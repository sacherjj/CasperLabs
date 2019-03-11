package io.casperlabs.node.configuration

import cats.data.Validated._
import cats.data._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.validated._
import io.casperlabs.configuration.ignore
import io.casperlabs.node.configuration.Utils._
import magnolia._

import scala.language.experimental.macros

private[configuration] trait ConfParser[A] {
  def parse(
      cliByName: String => Option[String],
      envVars: Map[String, String],
      configFile: Option[Map[String, String]],
      defaultConfigFile: Map[String, String],
      pathToField: List[String]
  ): ValidatedNel[String, A]
}

private[configuration] trait ConfParserImplicits {
  //Needed for .mapN, because it expects * -> * kind
  type ValidationResult[A] = ValidatedNel[String, A]

  implicit class OptionOps[A](o: Option[Either[String, A]]) {
    def toValidatedNel: ValidationResult[Option[A]] =
      o.fold(Valid(none[A]): ValidationResult[Option[A]])(_.map(_.some).toValidatedNel)
  }

  def parseEnv[A](
      envVars: Map[String, String],
      pathToField: List[String],
      P: Parser[A]
  ): ValidationResult[Option[A]] = {
    val key = "CL_" + snakify(pathToField.reverse.mkString("_"))
    envVars.get(key).map(P.parse).toValidatedNel
  }

  def parseToml[A](
      content: Map[String, String],
      pathToField: List[String],
      isDefault: Boolean,
      P: Parser[A]
  ): ValidationResult[Option[A]] = {
    val key = dashToCamel(pathToField.reverse.mkString("-"))
    content.get(key).map(P.parse).toValidatedNel
  }

  def parseCli[A](
      cliByName: String => Option[String],
      pathToField: List[String],
      P: Parser[A]
  ): ValidationResult[Option[A]] = {
    val fieldName = dashToCamel(pathToField.reverse.mkString("-"))
    cliByName(fieldName)
      .map(P.parse)
      .toValidatedNel
  }

  implicit def strict[A](
      implicit
      P: Parser[A]
  ): ConfParser[A] =
    (
        cliByName: String => Option[String],
        envVars: Map[String, String],
        configFile: Option[Map[String, String]],
        defaultConfigFile: Map[String, String],
        pathToField: List[String]
    ) =>
      fallbacking(P)
        .parse(cliByName, envVars, configFile, defaultConfigFile, pathToField) match {
        case Valid(a) =>
          a.fold(
            s"Failed to parse ${pathToField.reverse.mkString(".")} must be defined"
              .invalidNel[A]
          )(_.validNel)
        case invalid @ Invalid(_) => invalid
      }

  implicit def fallbacking[A](implicit P: Parser[A]): ConfParser[Option[A]] = {
    (
        cliByName: String => Option[String],
        envVars: Map[String, String],
        configFile: Option[Map[String, String]],
        defaultConfigFile: Map[String, String],
        pathToField: List[String]
    ) =>
      val fromCli =
        parseCli[A](cliByName, pathToField, P)
      val fromEnv = parseEnv(envVars, pathToField, P)
      val fromConfigFile =
        configFile
          .map(parseToml(_, pathToField, isDefault = false, P))
          .getOrElse(Valid(none[A]))
      val fromDefaultConfigFile =
        parseToml(defaultConfigFile, pathToField, isDefault = true, P)

      (fromCli, fromEnv, fromConfigFile, fromDefaultConfigFile).mapN {
        (maybeFromCli, maybeFromEnv, maybeFromConfigFile, maybeFromDefaultConfigFile) =>
          maybeFromCli
            .orElse(maybeFromEnv)
            .orElse(maybeFromConfigFile)
            .orElse(maybeFromDefaultConfigFile)
      }
  }
}

private[configuration] trait GenericConfParser extends ConfParserImplicits {
  type Typeclass[T] = ConfParser[T]

  def combine[T](caseClass: CaseClass[Typeclass, T]): Typeclass[T] = {
    (
        cliByName: String => Option[String],
        envVars: Map[String, String],
        configFile: Option[Map[String, String]],
        defaultConfigFile: Map[String, String],
        pathToField: List[String]
    ) =>
      val (errors, fields) = caseClass.parameters.foldLeft((List.empty[String], List.empty[Any])) {
        case ((es, fs), p) =>
          if (p.annotations.exists(_.isInstanceOf[ignore])) {
            p.default.fold((s"${p.label} must have default value if used with @ignore" :: es, fs))(
              d => (es, d :: fs)
            )
          } else {
            p.typeclass
              .parse(cliByName, envVars, configFile, defaultConfigFile, p.label :: pathToField) match {
              case Valid(a) =>
                (es, a :: fs)
              case Invalid(e) =>
                (e.toList ::: es, fs)
            }
          }
      }

      errors match {
        case x :: xs => Invalid(NonEmptyList(x, xs))
        case _       => Valid(caseClass.rawConstruct(fields.reverse))
      }
  }

  //We don't have sealed traits in the configuration class hierarchy
  def dispatch[T](sealedTrait: SealedTrait[Typeclass, T]): Typeclass[T] = ???

  implicit def gen[T]: Typeclass[T] = macro Magnolia.gen[T]
}

private[configuration] object ConfParser extends GenericConfParser
