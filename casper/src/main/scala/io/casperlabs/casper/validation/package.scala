package io.casperlabs.casper

import cats.{ApplicativeError, Functor}
import cats.mtl.FunctorRaise
import cats.syntax.applicativeError._
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper

package object validation {
  type RaiseValidationError[F[_]] = FunctorRaise[F, InvalidBlock]
  object RaiseValidationError {
    def apply[F[_]](implicit ev: RaiseValidationError[F]): RaiseValidationError[F] = ev
  }

  def raiseValidateErrorThroughApplicativeError[F[_]: ApplicativeError[*[_], Throwable]]
      : FunctorRaise[F, InvalidBlock] =
    new FunctorRaise[F, InvalidBlock] {
      override val functor: Functor[F] =
        Functor[F]

      override def raise[A](e: InvalidBlock): F[A] =
        ValidateErrorWrapper(e).raiseError[F, A]
    }
}
