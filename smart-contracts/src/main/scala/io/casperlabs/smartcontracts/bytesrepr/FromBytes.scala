package io.casperlabs.smartcontracts.bytesrepr

import cats.implicits._
import io.casperlabs.catscontrib.RangeOps.FoldM
import java.nio.charset.StandardCharsets
import scala.util.Try
import simulacrum.typeclass

@typeclass trait FromBytes[T] {
  def fromBytes(
      bytes: BytesView
  ): Either[FromBytes.Error, (T, BytesView)]
}

object FromBytes {
  sealed trait Error
  object Error {
    case object LeftOverBytes                                 extends Error
    case object NotEnoughBytes                                extends Error
    case class InvalidVariantTag(tag: Byte, typeName: String) extends Error
    case class FormatException(message: String)               extends Error
  }

  def deserialize[T: FromBytes](bytes: Array[Byte]): Either[Error, T] =
    FromBytes[T].fromBytes(BytesView(bytes)).flatMap {
      case (t, tail) =>
        if (tail.nonEmpty) Left(Error.LeftOverBytes)
        else Right(t)
    }

  implicit val fromBytesBool: FromBytes[Boolean] = new FromBytes[Boolean] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Boolean, BytesView)] =
      safePop(bytes).flatMap {
        case (tag, tail) if tag == Constants.Boolean.TRUE_TAG  => Right(true  -> tail)
        case (tag, tail) if tag == Constants.Boolean.FALSE_TAG => Right(false -> tail)
        case (other, _) =>
          Left(Error.FormatException(s"Byte $other could not be interpreted as a boolean"))
      }
  }

  implicit val fromBytesByte: FromBytes[Byte] = new FromBytes[Byte] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Byte, BytesView)] =
      safePop(bytes)
  }

  implicit val fromBytesInt: FromBytes[Int] = new FromBytes[Int] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Int, BytesView)] =
      for {
        (head, tail) <- safeTake(4, bytes)
        i            <- attempt(head.toByteBuffer.getInt)
      } yield i -> tail
  }

  implicit val fromBytesLong: FromBytes[Long] = new FromBytes[Long] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Long, BytesView)] =
      for {
        (head, tail) <- safeTake(8, bytes)
        i            <- attempt(head.toByteBuffer.getLong)
      } yield i -> tail
  }

  implicit val fromBytesBigInt: FromBytes[BigInt] = new FromBytes[BigInt] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (BigInt, BytesView)] =
      safePop(bytes).flatMap {
        case (0, tail) => Right(BigInt(0) -> tail)

        case (numBytes, tail) =>
          for {
            (littleEndian, rem) <- safeTake(numBytes.toInt, tail)
            bigEndian           = littleEndian.toArray.reverse
            // we prepend a 0 to indicate that the value is positive
            // (otherwise you can end up with weird things like 255 will deserialize to -1)
            i <- attempt(BigInt(0.toByte +: bigEndian))
          } yield i -> rem
      }
  }

  implicit val fromBytesUnit: FromBytes[Unit] = new FromBytes[Unit] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Unit, BytesView)] =
      Right(() -> bytes)
  }

  implicit val fromBytesString: FromBytes[String] = new FromBytes[String] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (String, BytesView)] =
      FromBytes[Int].fromBytes(bytes).flatMap {
        case (size, tail) if (size > tail.length) =>
          Left(
            Error.FormatException(
              s"Size of sequence $size is greater than the number of remaining bytes ${tail.length}"
            )
          )

        case (size, tail) =>
          for {
            (chars, rem) <- safeTake(size, tail)
            s            <- attempt(new String(chars.toArray, StandardCharsets.UTF_8))
          } yield s -> rem
      }
  }

  implicit def fromBytesOption[T: FromBytes]: FromBytes[Option[T]] = new FromBytes[Option[T]] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Option[T], BytesView)] =
      FromBytes[Byte].fromBytes(bytes).flatMap {
        case (tag, tail) if tag == Constants.Option.NONE_TAG =>
          Right(None -> tail)

        case (tag, tail) if tag == Constants.Option.SOME_TAG =>
          FromBytes[T].fromBytes(tail).map {
            case (t, rem) => (t.some, rem)
          }

        case (other, _) =>
          Left(Error.InvalidVariantTag(other, "Option"))
      }
  }

  implicit def fromBytesSeq[T: FromBytes]: FromBytes[Seq[T]] = new FromBytes[Seq[T]] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Seq[T], BytesView)] =
      FromBytes[Int].fromBytes(bytes).flatMap {
        case (size, tail) if (size > tail.length) =>
          Left(
            Error.FormatException(
              s"Size of sequence $size is greater than the number of remaining bytes ${tail.length}"
            )
          )

        case (size, tail) =>
          (0 until size).foldM(IndexedSeq.empty[T] -> tail) {
            case ((acc, rem), _) =>
              FromBytes[T].fromBytes(rem).map {
                case (t, rest) => (acc :+ t, rest)
              }
          }
      }
  }

  implicit def fromBytesEither[A: FromBytes, B: FromBytes]: FromBytes[Either[A, B]] =
    new FromBytes[Either[A, B]] {
      override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Either[A, B], BytesView)] =
        safePop(bytes).flatMap {
          case (tag, tail) if tag == Constants.Either.LEFT_TAG =>
            FromBytes[A].fromBytes(tail).map {
              case (a, rem) => (Left(a), rem)
            }

          case (tag, tail) if tag == Constants.Either.RIGHT_TAG =>
            FromBytes[B].fromBytes(tail).map {
              case (b, rem) => (Right(b), rem)
            }

          case (other, _) =>
            Left(Error.InvalidVariantTag(other, "Either"))
        }
    }

  implicit def fromBytesMap[K: FromBytes, V: FromBytes]: FromBytes[Map[K, V]] =
    new FromBytes[Map[K, V]] {
      override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Map[K, V], BytesView)] =
        FromBytes[Seq[(K, V)]].fromBytes(bytes).map {
          case (elems, rem) => (elems.toMap, rem)
        }
    }

  implicit def fromBytesTuple1[T1: FromBytes]: FromBytes[Tuple1[T1]] =
    new FromBytes[Tuple1[T1]] {
      override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Tuple1[T1], BytesView)] =
        FromBytes[T1].fromBytes(bytes).map {
          case (t, tail) => (Tuple1(t), tail)
        }
    }

  implicit def fromBytesTuple2[T1: FromBytes, T2: FromBytes]: FromBytes[(T1, T2)] =
    new FromBytes[(T1, T2)] {
      override def fromBytes(bytes: BytesView): Either[FromBytes.Error, ((T1, T2), BytesView)] =
        for {
          (t1, rem1) <- FromBytes[T1].fromBytes(bytes)
          (t2, rem2) <- FromBytes[T2].fromBytes(rem1)
        } yield ((t1, t2), rem2)
    }

  implicit def fromBytesTuple3[
      T1: FromBytes,
      T2: FromBytes,
      T3: FromBytes
  ]: FromBytes[(T1, T2, T3)] =
    new FromBytes[(T1, T2, T3)] {
      override def fromBytes(bytes: BytesView): Either[FromBytes.Error, ((T1, T2, T3), BytesView)] =
        for {
          (t1, rem1) <- FromBytes[T1].fromBytes(bytes)
          (t2, rem2) <- FromBytes[T2].fromBytes(rem1)
          (t3, rem3) <- FromBytes[T3].fromBytes(rem2)
        } yield ((t1, t2, t3), rem3)
    }

  private def attempt[T](block: => T): Either[Error, T] =
    Try(block).toEither.leftMap(err => Error.FormatException(err.getMessage))

  def safeTake(
      n: Int,
      bytes: BytesView
  ): Either[Error.NotEnoughBytes.type, (BytesView, BytesView)] =
    bytes.safeTake(n) match {
      case None         => Left(Error.NotEnoughBytes)
      case Some(result) => Right(result)
    }

  def safePop(
      bytes: BytesView
  ): Either[Error.NotEnoughBytes.type, (Byte, BytesView)] =
    bytes.pop match {
      case None         => Left(Error.NotEnoughBytes)
      case Some(result) => Right(result)
    }
}
