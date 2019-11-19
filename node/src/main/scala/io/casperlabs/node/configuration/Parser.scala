package io.casperlabs.node.configuration

import java.nio.file.{Path, Paths}

import scala.util.Try
import cats._
import cats.implicits._
import cats.syntax._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils._
import eu.timepit.refined._
import eu.timepit.refined.numeric._
import eu.timepit.refined.api.Refined
import scala.concurrent.duration.Duration.Infinite
import scala.concurrent.duration._
import izumi.logstage.api.{Log => IzLog}

private[configuration] trait Parser[A] {
  def parse(s: String): Either[String, A]
}

private[configuration] trait ParserImplicits {
  implicit val stringParser: Parser[String] = _.asRight[String]
  implicit val intParser: Parser[Int]       = s => Try(s.toInt).toEither.leftMap(_.getMessage)
  implicit val bigIntParser: Parser[BigInt] = s => Try(BigInt(s)).toEither.leftMap(_.getMessage)
  implicit val longParser: Parser[Long]     = s => Try(s.toLong).toEither.leftMap(_.getMessage)
  implicit val doubleParser: Parser[Double] = s => Try(s.toDouble).toEither.leftMap(_.getMessage)
  implicit val booleanParser: Parser[Boolean] = {
    case "true"  => true.asRight[String]
    case "false" => false.asRight[String]
    case s =>
      s"Failed to parse '$s' as Boolean, must be 'true' or 'false'"
        .asLeft[Boolean]
  }
  implicit val finiteDurationParser: Parser[FiniteDuration] = s =>
    Try(Duration(s)).toEither
      .leftMap(_.getMessage)
      .flatMap {
        case fd: FiniteDuration => fd.asRight[String]
        case _: Infinite        => "Got Infinite, expected FiniteDuration".asLeft[FiniteDuration]
      }
  implicit val pathParser: Parser[Path] = s =>
    Try(Paths.get(s.replace("$HOME", sys.props("user.home")))).toEither
      .leftMap(_.getMessage)

  implicit val peerNodeParser: Parser[NodeWithoutChainId] = s => {
    Node.fromAddress(s)
  }

  implicit val protocolVersionParser: Parser[ChainSpec.ProtocolVersion] = {
    // Major and minor mandatory, patch optional.
    val SemVer = """(\d+)\.(\d+)(?:\.(\d+))?""".r
    s =>
      s match {
        case SemVer(major, minor, patch) =>
          ChainSpec
            .ProtocolVersion(major.toInt, minor.toInt, Option(patch).fold(0)(_.toInt))
            .asRight
        case _ =>
          s"Unable to parse semver: $s".asLeft
      }
  }

  implicit val positiveIntParser: Parser[Refined[Int, Positive]] =
    s =>
      for {
        i <- Try(s.toInt).toEither.leftMap(_.getMessage)
        p <- refineV[Positive](i)
      } yield p

  implicit val nonNegativeIntParser: Parser[Refined[Int, NonNegative]] =
    s =>
      for {
        i <- Try(s.toInt).toEither.leftMap(_.getMessage)
        p <- refineV[NonNegative](i)
      } yield p

  implicit val gte1DoubleParser: Parser[Refined[Double, GreaterEqual[W.`1.0`.T]]] =
    s =>
      for {
        d <- Try(s.toDouble).toEither.leftMap(_.getMessage)
        w <- refineV[GreaterEqual[W.`1.0`.T]](d)
      } yield w

  implicit val gte0DoubleParser: Parser[Refined[Double, GreaterEqual[W.`0.0`.T]]] =
    s =>
      for {
        d <- Try(s.toDouble).toEither.leftMap(_.getMessage)
        w <- refineV[GreaterEqual[W.`0.0`.T]](d)
      } yield w

  implicit def listParser[T: Parser] = new Parser[List[T]] {
    override def parse(s: String) =
      s.split(' ').filterNot(_.isEmpty).map(Parser[T].parse).toList.sequence
  }

  implicit val logLevelParser = new Parser[IzLog.Level] {
    override def parse(s: String) =
      Try(IzLog.Level.parse(s)).toEither.leftMap(_.getMessage)
  }
}

private[configuration] object Parser {
  def apply[A](implicit P: Parser[A]): Parser[A] = P
}
