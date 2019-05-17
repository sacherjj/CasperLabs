package io.casperlabs.node.api

import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import com.google.protobuf.empty.Empty
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import io.casperlabs.node.api.casper._
import monix.execution.Scheduler
import monix.eval.{Task, TaskLike}

object GrpcCasperService {
  def apply[F[_]: Concurrent: TaskLike: Log: Metrics: MultiParentCasperRef](
      blockApiLock: Semaphore[F]
  ): F[CasperGrpcMonix.CasperService] =
    BlockAPI.establishMetrics[F] *> Sync[F].delay {
      new CasperGrpcMonix.CasperService {
        override def deploy(request: DeployRequest): Task[Empty] =
          TaskLike[F].toTask {
            BlockAPI.deploy[F](request.getDeploy).map(_ => Empty())
          }
      }
    }
}
