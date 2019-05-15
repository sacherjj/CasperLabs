package io.casperlabs.node.api

import cats.effect.concurrent._
import io.casperlabs.node.api.casper.CasperGrpcMonix
import monix.execution.Scheduler

object GrpcCasperService {
  def apply[F[_]](blockApiLock: Semaphore[F]): F[CasperGrpcMonix.CasperService] = ???
}
