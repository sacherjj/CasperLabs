package io.casperlabs.casper

import io.casperlabs.casper.consensus.info.Event
import simulacrum.typeclass

@typeclass trait EventEmitterContainer[F[_]] {
  def get: F[Event]
  def set(event: Event): F[Unit]
}
