package io.casperlabs.casper

import cats.MonadError
import cats.mtl.FunctorRaise
import io.casperlabs.casper.validation.ValidationImpl
import io.casperlabs.shared.{Log, Time}

object DeriveValidation {
  implicit def deriveValidationImpl[F[_]](
      implicit
      fr: FunctorRaise[F, InvalidBlock],
      log: Log[F],
      mt: MonadError[F, Throwable],
      time: Time[F]
  ) = new ValidationImpl[F]
}
