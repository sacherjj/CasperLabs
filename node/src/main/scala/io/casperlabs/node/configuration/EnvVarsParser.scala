package io.casperlabs.node.configuration

import java.nio.file.{Path, Paths}

import cats.data.Validated.Valid
import cats.data.ValidatedNel
import cats.syntax.either._
import cats.syntax.apply._
import cats.syntax.validated._
import cats.syntax.option._
import io.casperlabs.comm.{CommError, PeerNode}
import io.casperlabs.shared.StoreType
import shapeless._
import shapeless.labelled.FieldType

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

trait EnvVarsParser[A] {
  def parse(env: Map[String, String], path: List[String]): ValidatedNel[String, Option[A]]
}

trait EnvVarsParserImplicits {
  def snakify(s: String): String =
    s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
      .toUpperCase

  protected def instance[A](f: String => Either[String, A]): EnvVarsParser[A] =
    (env: Map[String, String], path: List[String]) => {
      env
        .get(snakify(path.reverse.mkString("_")))
        .map(f(_).map(_.some).toValidatedNel)
        .getOrElse(none[A].validNel)
    }

  implicit val stringParser: EnvVarsParser[String] =
    instance(_.asRight[String])
  implicit val intParser: EnvVarsParser[Int] =
    instance(s => Try(s.toInt).toEither.leftMap(_.getMessage))
  implicit val longParser: EnvVarsParser[Long] = instance(
    s => Try(s.toLong).toEither.leftMap(_.getMessage)
  )
  implicit val doubleParser: EnvVarsParser[Double] = instance(
    s => Try(s.toDouble).toEither.leftMap(_.getMessage)
  )
  implicit val booleanParser: EnvVarsParser[Boolean] = instance {
    case "true"  => true.asRight[String]
    case "false" => false.asRight[String]
    case s       => s"Failed to parse '$s' as Boolean, must be 'true' or 'false'".asLeft[Boolean]
  }
  implicit val finiteDurationParser: EnvVarsParser[FiniteDuration] = instance { s =>
    Duration(s) match {
      case fd: FiniteDuration => fd.asRight[String]
      case _                  => s"Failed to parse $s as FiniteDuration.".asLeft[FiniteDuration]
    }
  }
  implicit val pathParser: EnvVarsParser[Path] = instance(
    s => Try(Paths.get(s)).toEither.leftMap(_.getMessage)
  )
  implicit val peerNodeParser: EnvVarsParser[PeerNode] = instance(
    s => PeerNode.fromAddress(s).leftMap(CommError.errorMessage)
  )
  implicit val storeTypeParser: EnvVarsParser[StoreType] = instance(
    s =>
      StoreType
        .from(s)
        .fold(s"Failed to parse '$s' as StoreType".asLeft[StoreType])(_.asRight[String])
  )

  implicit def optionParser[A: EnvVarsParser]: EnvVarsParser[Option[A]] =
    (env: Map[String, String], path: List[String]) => EnvVarsParser[A].parse(env, path).map(_.some)

  implicit def hnilParser: EnvVarsParser[HNil] = instance(_ => HNil.asRight[String])

  implicit def hconsParser[K <: Symbol, H, T <: HList](
      implicit
      witness: Witness.Aux[K],
      head: Lazy[EnvVarsParser[H]],
      tail: EnvVarsParser[T]
  ): EnvVarsParser[FieldType[K, H] :: T] =
    (env: Map[String, String], path: List[String]) => {
      val prefix = witness.value.name
      val h      = head.value.parse(env, prefix :: path)
      val t      = tail.parse(env, path)

      (h, t).mapN {
        case (Some(a), Some(b)) => Option(labelled.field[K](a) :: b)
        case (Some(a), None)    => Option(labelled.field[K](a) :: HNil.asInstanceOf[T])
      }
    }

  implicit def labelledGenericParser[A, Repr <: HList](
      implicit gen: LabelledGeneric.Aux[A, Repr],
      parser: EnvVarsParser[Repr]
  ): EnvVarsParser[A] =
    (env: Map[String, String], path: List[String]) => {
      parser.parse(env, path).map(_.map(gen.from))
    }
}

object EnvVarsParser extends EnvVarsParserImplicits {
  def apply[A](implicit envVarsParser: EnvVarsParser[A]): EnvVarsParser[A] = envVarsParser
}
