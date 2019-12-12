package io.casperlabs.casper.helper

import cats.Applicative
import cats.implicits._
import io.casperlabs.casper.EventEmitter
import io.casperlabs.casper.consensus.BlockSummary

object NoOpsEventEmitter {
  def create[F[_]: Applicative](): EventEmitter[F] =
    new EventEmitter[F] {
      override def blockAdded(blockSummary: BlockSummary): F[Unit] = ().pure[F]
    }
}
