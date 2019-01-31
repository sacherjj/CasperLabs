package io.casperlabs.node.api

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.google.protobuf.empty.Empty
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.protocol.{DeployData, DeployServiceResponse, _}
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib.Taskable
import io.casperlabs.shared._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

private[api] object DeployGrpcService {
  def instance[F[_]: Concurrent: MultiParentCasperRef: Log: SafetyOracle: BlockStore: Taskable](
      blockApiLock: Semaphore[F]
  )(
      implicit worker: Scheduler
  ): CasperMessageGrpcMonix.DeployService =
    new CasperMessageGrpcMonix.DeployService {

      private def defer[A](task: F[A]): Task[A] =
        Task.defer(task.toTask).executeOn(worker).attemptAndLog

      override def doDeploy(d: DeployData): Task[DeployServiceResponse] =
        defer(BlockAPI.deploy[F](d))

      override def createBlock(e: Empty): Task[DeployServiceResponse] =
        defer(BlockAPI.createBlock[F](blockApiLock))

      override def showBlock(q: BlockQuery): Task[BlockQueryResponse] =
        defer(BlockAPI.showBlock[F](q))

      // TODO handle potentiall errors (at least by returning proper response)
      override def visualizeBlocks(q: BlocksQuery): Task[VisualizeBlocksResponse] = {
        val depth = if (q.depth <= 0) None else Some(q.depth)
        defer(BlockAPI.visualizeBlocks[F](depth).map(VisualizeBlocksResponse(_)))
      }

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
