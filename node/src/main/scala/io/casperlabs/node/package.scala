package io.casperlabs

import cats.Monad
import cats.data.EitherT
import com.google.protobuf.{ByteString, CodedInputStream, CodedOutputStream}
import monix.eval.{Task, TaskLike}
import pbdirect.{PBReader, PBWriter}

package object node {

  implicit def eitherTTaskable[F[_]: Monad: TaskLike, E]: TaskLike[EitherT[F, E, ?]] =
    new TaskLike[EitherT[F, E, ?]] {
      case class ToTaskException(e: E) extends RuntimeException

      def apply[A](fa: EitherT[F, E, A]): Task[A] =
        TaskLike[F]
          .apply(fa.value)
          .flatMap {
            case Right(a) => Task.now(a)
            case Left(e)  => Task.raiseError[A](ToTaskException(e))
          }

    }

  implicit object ByteStringReader extends PBReader[ByteString] {
    override def read(input: CodedInputStream): ByteString =
      ByteString.copyFrom(input.readByteArray())
  }

  implicit object ByteStringWriter extends PBWriter[ByteString] {
    override def writeTo(index: Int, value: ByteString, out: CodedOutputStream): Unit =
      out.writeByteArray(index, value.toByteArray)
  }
}
