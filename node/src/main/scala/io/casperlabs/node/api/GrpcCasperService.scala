package io.casperlabs.node.api

import cats.effect._
import cats.implicits._
import com.google.protobuf.empty.Empty
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.info._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.casper._
import io.casperlabs.shared.Log
import monix.eval.{Task, TaskLike}
import monix.reactive.Observable

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

        override def getBlockInfo(request: GetBlockInfoRequest): Task[BlockInfo] =
          TaskLike[F].toTask {
            BlockAPI
              .getBlockInfo[F](
                request.blockHashBase16,
                full = request.view == BlockInfoView.FULL
              )
          }

        override def streamBlockInfos(request: StreamBlockInfosRequest): Observable[BlockInfo] = {
          val infos = TaskLike[F].toTask {
            BlockAPI.getBlockInfos[F](
              depth = request.depth,
              maxRank = request.maxRank,
              full = request.view == BlockInfoView.FULL
            )
          }
          Observable.fromTask(infos).flatMap(Observable.fromIterable)
        }
      }
    }
}
