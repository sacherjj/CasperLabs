package io.casperlabs.node.api

import cats.effect.Sync
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.casper.protocol.{DeployData, DeployServiceResponse, _}
import io.casperlabs.catscontrib.Taskable
import io.casperlabs.shared._
import io.casperlabs.catscontrib.TaskContrib._
import com.google.protobuf.empty.Empty
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

private[api] object DeployGrpcService {
  def instance[F[_]: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore: Taskable](
      implicit worker: Scheduler
  ): CasperMessageGrpcMonix.DeployService =
    new CasperMessageGrpcMonix.DeployService {

      private def defer[A](task: F[A]): Task[A] =
        Task.defer(task.toTask).executeOn(worker).attemptAndLog

      override def doDeploy(d: DeployData): Task[DeployServiceResponse] =
        defer(BlockAPI.deploy[F](d))

      override def createBlock(e: Empty): Task[DeployServiceResponse] =
        defer(BlockAPI.createBlock[F])

      override def addBlock(b: BlockMessage): Task[DeployServiceResponse] =
        defer(BlockAPI.addBlock[F](b))

      override def showBlock(q: BlockQuery): Task[BlockQueryResponse] =
        defer(BlockAPI.showBlock[F](q))

      override def showBlocks(request: BlocksQuery): Observable[BlockInfoWithoutTuplespace] =
        Observable
          .fromTask(defer(BlockAPI.showBlocks[F](request.depth)))
          .flatMap(Observable.fromIterable)

      override def showMainChain(request: BlocksQuery): Observable[BlockInfoWithoutTuplespace] =
        Observable
          .fromTask(defer(BlockAPI.showMainChain[F](request.depth)))
          .flatMap(Observable.fromIterable)

      override def findBlockWithDeploy(request: FindDeployInBlockQuery): Task[BlockQueryResponse] =
        defer(BlockAPI.findBlockWithDeploy[F](request.user, request.timestamp))

      override def previewPrivateNames(
          request: PrivateNamePreviewQuery
      ): Task[PrivateNamePreviewResponse] =
        defer(BlockAPI.previewPrivateNames[F](request.user, request.timestamp, request.nameQty))
    }
}
