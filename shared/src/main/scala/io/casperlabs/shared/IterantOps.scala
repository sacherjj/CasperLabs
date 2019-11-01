package io.casperlabs.shared

import cats.effect._
import cats.implicits._
import monix.tail.Iterant

object IterantOps {
  implicit class RichIterant[F[_]: Sync, A](it: Iterant[F, A]) {
    def foldLeftM[B](b: B)(f: (B, A) => F[B]): F[B] = it.foldWhileLeftEvalL(Sync[F].pure(b)) {
      case (s, a) => f(s, a).map(_.asLeft[B])
    }
  }
}
