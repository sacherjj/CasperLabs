package io.casperlabs.node.api.graphql

import cats.effect.Effect
import cats.effect.implicits._
import simulacrum.typeclass

import scala.concurrent.Future

@typeclass trait RunToFuture[F[_]] {
  def unsafeToFuture[A](fa: F[A]): Future[A]
}

object RunToFuture {
  implicit def fromEffect[F[_]: Effect]: RunToFuture[F] = new RunToFuture[F] {
    override def unsafeToFuture[A](fa: F[A]): Future[A] = fa.toIO.unsafeToFuture()
  }
}
