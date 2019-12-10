package io.casperlabs.casper

import io.casperlabs.casper.consensus.info.Event
import simulacrum.typeclass

@typeclass trait EventEmitterContainer[F[_]] {
  def publish(event: Event): F[Unit]
}
