package io.casperlabs.shared

import cats.effect.Sync
import simulacrum.typeclass

// Fatal error that should shut down the node.
case class FatalErrorShutdown(message: String) extends Exception(message)

@typeclass trait FatalErrorHandler[F[_]] {
  def handle(error: FatalErrorShutdown): F[Unit]
}

object FatalErrorHandler {
  class FatalErrorHandlerNoop[F[_]: Sync] extends FatalErrorHandler[F] {
    override def handle(error: FatalErrorShutdown): F[Unit] = Sync[F].unit
  }
}
