package io.casperlabs.node.effects

import cats._
import cats.data._
import cats.implicits._
import io.casperlabs.catscontrib._

trait ConsoleIO[F[_]] {
  def println(str: String): F[Unit]
}

object ConsoleIO extends ConsoleIO0 {
  def apply[F[_]](implicit ev: ConsoleIO[F]): ConsoleIO[F] = ev
}

trait ConsoleIO0 {
  import eitherT._
  implicit def eitherTConsoleIO[F[_]: Monad: ConsoleIO, E]: ConsoleIO[EitherT[F, E, ?]] =
    ForTrans.forTrans[F, EitherT[?[_], E, ?]]
}

class NOPConsoleIO[F[_]: Applicative] extends ConsoleIO[F] {
  def println(str: String): F[Unit] = ().pure[F]
}

object ForTrans {
  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](implicit C: ConsoleIO[F]): ConsoleIO[T[F, ?]] =
    new ConsoleIO[T[F, ?]] {
      def println(str: String): T[F, Unit] = MonadTrans[T].liftM(ConsoleIO[F].println(str))
    }
}
