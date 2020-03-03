package io.casperlabs.models.bytesrepr

import cats.arrow.FunctionK
import cats.data.StateT
import cats.free.Free
import cats.implicits._
import io.casperlabs.catscontrib.RangeOps.FoldM
import java.nio.charset.StandardCharsets
import scala.util.{Failure, Success, Try}
import scala.util.Try
import simulacrum.typeclass

@typeclass trait FromBytes[T] {
  def fromBytes(
      bytes: BytesView
  ): Either[FromBytes.Error, (T, BytesView)]
}

object FromBytes {
  // Algebra defining operations used in de-serialization
  sealed trait Algebra[A]
  // Do nothing
  case object Id extends Algebra[Unit]
  // Take the first byte from the view
  case class Pop(bytes: BytesView) extends Algebra[(BytesView, Byte)]
  // create a new view from the first `n` bytes
  case class Take(n: Int, bytes: BytesView) extends Algebra[(BytesView, BytesView)]
  // raise an error
  case class Raise[A](error: Error) extends Algebra[A]

  // The context for de-serialization is the free monad over our algebra (gives
  // stack safety). The state for the computation is `BytesView`. The return value
  // is of type `A`.
  type Deserializer[A] = StateT[Free[Algebra, *], BytesView, A]

  // Given a deserializer and some bytes, attempt to parse a value of type A
  def deserialize[A](des: Deserializer[A], bytes: Array[Byte]): Either[Error, A] =
    des.run(BytesView(bytes)).foldMap(interpreter).flatMap {
      case (rem, _) if rem.nonEmpty => Left(Error.LeftOverBytes)
      case (_, a)                   => Right(a)
    }

  // Lift basic operations of the algebra as Deserializers
  val id: Deserializer[Unit]                  = StateT.liftF(Free.liftF(Id))
  val byte: Deserializer[Byte]                = StateT(bytes => Free.liftF(Pop(bytes)))
  def take(n: Int): Deserializer[BytesView]   = StateT(bytes => Free.liftF(Take(n, bytes)))
  def raise[A](error: Error): Deserializer[A] = StateT.liftF(Free.liftF(Raise[A](error)))

  // Convenience function for defining Deserializers outside this package without importing StateT
  def pure[A](a: A): Deserializer[A] = StateT.pure(a)

  // get the current state of the bytes stream (without modifying it)
  val getState: Deserializer[BytesView] = StateT.get

  // Catch exceptions in the Monad of our Deserializer
  private def attempt[A](block: => A): Free[Algebra, A] = Try(block) match {
    case Success(a)   => Free.pure(a)
    case Failure(err) => Free.liftF(Raise(Error.FormatException(err.getMessage)))
  }

  val bool: Deserializer[Boolean] =
    byte.flatMap {
      case tag if tag == Constants.Boolean.TRUE_TAG  => pure(true)
      case tag if tag == Constants.Boolean.FALSE_TAG => pure(false)
      case other =>
        raise(Error.FormatException(s"Byte $other could not be interpreted as a boolean"))
    }

  val int: Deserializer[Int] = take(4).flatMapF(view => attempt(view.toByteBuffer.getInt))

  val long: Deserializer[Long] = take(8).flatMapF(view => attempt(view.toByteBuffer.getLong))

  val bigInt: Deserializer[BigInt] =
    byte.flatMap {
      case numBytes if numBytes < 0 =>
        raise(Error.FormatException("Negative number of BigInt Bytes"))

      case 0 => pure(BigInt(0))

      case numBytes =>
        take(numBytes.toInt).flatMapF { littleEndian =>
          val bigEndian = littleEndian.toArray.reverse
          // we prepend a 0 to indicate that the value is positive
          // (otherwise you can end up with weird things like 255 will deserialize to -1)
          attempt(BigInt(0.toByte +: bigEndian))
        }
    }

  val unit: Deserializer[Unit] = id

  val bytes: Deserializer[Array[Byte]] =
    for {
      size        <- int
      stateLength <- getState.map(_.length)
      view <- if (size < 0) raise(Error.FormatException("Negative length of ByteArray"))
             else if (size > stateLength)
               raise(
                 Error.FormatException(
                   s"Size of ByteArray $size is greater than the number of remaining bytes $stateLength"
                 )
               )
             else take(size)
    } yield view.toArray

  val string: Deserializer[String] =
    bytes.flatMapF(chars => attempt(new String(chars.toArray, StandardCharsets.UTF_8)))

  // Pass in `desA` lazily for stack safety when recursively chaining Deserializers.
  // It is also more efficient in the None case because we do not evaluate `desA`.
  def option[A](desA: => Deserializer[A]): Deserializer[Option[A]] =
    byte.flatMap {
      case tag if tag == Constants.Option.NONE_TAG =>
        pure(none[A])

      case tag if tag == Constants.Option.SOME_TAG =>
        desA.map(_.some)

      case other =>
        raise(Error.InvalidVariantTag(other, "Option"))
    }

  def fixedSeq[A](desA: => Deserializer[A], size: Int): Deserializer[Seq[A]] =
    (0 until size)
      .foldLeft(pure(IndexedSeq.empty[A])) {
        case (acc, _) =>
          acc.flatMap { xs =>
            desA.map(a => xs :+ a)
          }
      }
      .map(_.toSeq)

  def seq[A](desA: => Deserializer[A]): Deserializer[Seq[A]] =
    for {
      size        <- int
      stateLength <- getState.map(_.length)
      xs <- if (size < 0) raise(Error.FormatException("Negative length of Sequence"))
           else if (size > stateLength)
             raise(
               Error.FormatException(
                 s"Size of Sequence $size is greater than the number of remaining bytes $stateLength"
               )
             )
           else fixedSeq(desA, size)

    } yield xs

  def either[A, B](desA: => Deserializer[A], desB: => Deserializer[B]): Deserializer[Either[A, B]] =
    byte.flatMap {
      case tag if tag == Constants.Either.LEFT_TAG =>
        desA.map(a => Left(a).rightCast[B])

      case tag if tag == Constants.Either.RIGHT_TAG =>
        desB.map(b => Right(b).leftCast[A])

      case other =>
        raise(Error.InvalidVariantTag(other, "Either"))
    }

  def map[A, B](desA: => Deserializer[A], desB: => Deserializer[B]): Deserializer[Map[A, B]] =
    seq(tuple2(desA, desB)).map(_.toMap)

  def tuple1[A](desA: => Deserializer[A]): Deserializer[Tuple1[A]] =
    for {
      _ <- id // put a flatMap before `desA` so that it will not be immediately evaluated
      a <- desA
    } yield Tuple1(a)

  def tuple2[A, B](desA: => Deserializer[A], desB: => Deserializer[B]): Deserializer[(A, B)] =
    for {
      _ <- id
      a <- desA
      b <- desB
    } yield (a, b)

  def tuple3[A, B, C](
      desA: => Deserializer[A],
      desB: => Deserializer[B],
      desC: Deserializer[C]
  ): Deserializer[(A, B, C)] =
    for {
      _ <- id
      a <- desA
      b <- desB
      c <- desC
    } yield (a, b, c)

  // interpreter for our algebra converting each operation into a result
  val interpreter: FunctionK[Algebra, Either[Error, *]] =
    new FunctionK[Algebra, Either[Error, *]] {
      def apply[A](instruction: Algebra[A]): Either[Error, A] = instruction match {
        case Pop(bytes) =>
          bytes.pop match {
            case None         => Left(Error.NotEnoughBytes)
            case Some(result) => Right(result)
          }

        case Take(n, bytes) =>
          bytes.safeTake(n) match {
            case None         => Left(Error.NotEnoughBytes)
            case Some(result) => Right(result)
          }

        case Raise(err) => Left(err)

        case Id => Right(())
      }
    }

  sealed trait Error
  object Error {
    case object LeftOverBytes                                 extends Error
    case object NotEnoughBytes                                extends Error
    case class InvalidVariantTag(tag: Byte, typeName: String) extends Error
    case class FormatException(message: String)               extends Error
  }
}
