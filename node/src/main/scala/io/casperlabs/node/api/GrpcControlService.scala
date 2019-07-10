package io.casperlabs.node.api

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.control._
import io.casperlabs.shared.Log
import monix.eval.{Task, TaskLike}

object GrpcControlService {
  def apply[F[_]: Sync: TaskLike: Log: Metrics: MultiParentCasperRef](
      blockApiLock: Semaphore[F]
  ): F[ControlGrpcMonix.ControlService] =
    BlockAPI.establishMetrics[F] *> Sync[F].delay {
      new ControlGrpcMonix.ControlService {
        override def propose(request: ProposeRequest): Task[ProposeResponse] =
          TaskLike[F].toTask {
            BlockAPI.propose[F](blockApiLock).map(ProposeResponse().withBlockHash(_))
          }
      }
    }
}
