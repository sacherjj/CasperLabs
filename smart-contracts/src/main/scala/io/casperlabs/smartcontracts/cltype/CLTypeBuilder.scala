package io.casperlabs.smartcontracts.cltype

import cats.data.NonEmptyList
import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes}
import scala.annotation.tailrec

sealed trait CLTypeBuilder {
  def buildFrom(subType: CLType): CLTypeBuilder.Result
}

object CLTypeBuilder {
  sealed trait Result
  object Result {
    case class Complete(t: CLType)                          extends Result
    case class LengthNeeded(build: Int => CLType.FixedList) extends Result
    case class Continue(builder: CLTypeBuilder)             extends Result
  }

  case object Hole extends CLTypeBuilder {
    override def buildFrom(subType: CLType) = Result.Complete(subType)
  }

  case object OptionHole extends CLTypeBuilder {
    override def buildFrom(subType: CLType) = Result.Complete(CLType.Option(subType))
  }

  case object ListHole extends CLTypeBuilder {
    override def buildFrom(subType: CLType) = Result.Complete(CLType.List(subType))
  }

  case object FixedListHole extends CLTypeBuilder {
    override def buildFrom(subType: CLType) = Result.LengthNeeded(n => CLType.FixedList(subType, n))
  }

  case class ResultErrHole(ok: CLType) extends CLTypeBuilder {
    override def buildFrom(err: CLType) = Result.Complete(CLType.Result(ok, err))
  }

  case object ResultHole extends CLTypeBuilder {
    override def buildFrom(ok: CLType) = Result.Continue(ResultErrHole(ok))
  }

  case class MapValueHole(key: CLType) extends CLTypeBuilder {
    override def buildFrom(value: CLType) = Result.Complete(CLType.Map(key, value))
  }

  case object MapHole extends CLTypeBuilder {
    override def buildFrom(key: CLType) = Result.Continue(MapValueHole(key))
  }

  case object Tuple1Hole extends CLTypeBuilder {
    override def buildFrom(subType: CLType) = Result.Complete(CLType.Tuple1(subType))
  }

  case class Tuple2Hole2(t1: CLType) extends CLTypeBuilder {
    override def buildFrom(t2: CLType) = Result.Complete(CLType.Tuple2(t1, t2))
  }

  case object Tuple2Hole extends CLTypeBuilder {
    override def buildFrom(t1: CLType) = Result.Continue(Tuple2Hole2(t1))
  }

  case class Tuple3Hole3(t1: CLType, t2: CLType) extends CLTypeBuilder {
    override def buildFrom(t3: CLType) = Result.Complete(CLType.Tuple3(t1, t2, t3))
  }

  case class Tuple3Hole2(t1: CLType) extends CLTypeBuilder {
    override def buildFrom(t2: CLType) = Result.Continue(Tuple3Hole3(t1, t2))
  }

  case object Tuple3Hole extends CLTypeBuilder {
    override def buildFrom(t1: CLType) = Result.Continue(Tuple3Hole2(t1))
  }

  type Return = (Either[NonEmptyList[CLTypeBuilder], CLType], BytesView)

  @tailrec
  def buildFrom(
      subType: CLType,
      builders: NonEmptyList[CLTypeBuilder],
      bytes: BytesView
  ): Either[FromBytes.Error, Return] =
    builders.head.buildFrom(subType) match {
      case Result.Complete(t) =>
        builders.tail match {
          case Nil => Right((Right(t), bytes))

          case head :: tail => buildFrom(t, NonEmptyList(head, tail), bytes)
        }

      case Result.Continue(b) => Right(Left(NonEmptyList(b, builders.tail)) -> bytes)

      case Result.LengthNeeded(f) =>
        FromBytes[Int].fromBytes(bytes) match {
          case Left(err) => Left(err)

          case Right((n, remBytes)) =>
            builders.tail match {
              case Nil => Right(Right(f(n)) -> remBytes)

              case head :: tail => buildFrom(f(n), NonEmptyList(head, tail), remBytes)
            }
        }
    }
}
