package io.casperlabs.casper.helper

import cats.Applicative
import cats.implicits._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.FinalizationHandler

object NoOpsFinalizationHandler {
  implicit def finalizationHandler[F[_]: Applicative]: FinalizationHandler[F] =
    (_: BlockHash) => ().pure[F]
}
