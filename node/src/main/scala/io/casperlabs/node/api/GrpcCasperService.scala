package io.casperlabs.node.api

import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import com.google.protobuf.empty.Empty
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import io.casperlabs.node.api.casper._
import monix.execution.Scheduler
import monix.eval.{Task, TaskLike}

object GrpcCasperService {
  def apply[F[_]: Concurrent: TaskLike: Log: Metrics: MultiParentCasperRef: SafetyOracle: BlockStore](
      ignoreDeploySignature: Boolean
  ): F[CasperGrpcMonix.CasperService] =
    BlockAPI.establishMetrics[F] *> Sync[F].delay {
      new CasperGrpcMonix.CasperService {
        override def deploy(request: DeployRequest): Task[Empty] =
          TaskLike[F].toTask {
            BlockAPI.deploy[F](request.getDeploy, ignoreDeploySignature).map(_ => Empty())
          }

        override def getBlockInfo(request: GetBlockInfoRequest): monix.eval.Task[BlockInfo] =
          TaskLike[F].toTask {
            BlockAPI
              .getBlockInfo[F](
                request.blockHashBase16,
                full = request.view == GetBlockInfoRequest.View.FULL
              )
          }
      }
    }
}
