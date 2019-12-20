package io.casperlabs.casper.helper

import cats.Applicative
import cats.implicits._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.EventEmitter
import io.casperlabs.casper.consensus.info.BlockInfo

object NoOpsEventEmitter {
  def create[F[_]: Applicative](): EventEmitter[F] =
    new EventEmitter[F] {
      override def blockAdded(block: BlockInfo): F[Unit] = ().pure[F]

      override def newLFB(lfb: BlockHash, indirectlyFinalized: Set[BlockHash]): F[Unit] = ().pure[F]
    }
}
