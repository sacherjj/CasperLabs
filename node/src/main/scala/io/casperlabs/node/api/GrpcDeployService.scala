package io.casperlabs.node.api

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.protocol._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.comm.ServiceError.Unimplemented
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.Utils.toKey
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler
import monix.reactive.Observable

@silent("deprecated")
object GrpcDeployService {

  def splitPath(path: String): Seq[String] =
    path.split("/").filter(_.nonEmpty)

  def instance[F[_]: Concurrent: MultiParentCasperRef: Log: Metrics: SafetyOracle: BlockStore: TaskLike: ExecutionEngineService](
      blockApiLock: Semaphore[F],
      ignoreDeploySignature: Boolean
  )(
      implicit worker: Scheduler
  ): F[CasperMessageGrpcMonix.DeployService] = {
    def mkService = new CasperMessageGrpcMonix.DeployService {
      private def defer[A](task: F[A]): Task[A] =
        Task.defer(TaskLike[F].toTask(task)).executeOn(worker).attemptAndLog

      override def doDeploy(d: DeployData): Task[DeployServiceResponse] =
        defer(BlockAPI.deploy[F](d, ignoreDeploySignature))

      override def createBlock(e: Empty): Task[DeployServiceResponse] =
        defer(BlockAPI.createBlock[F](blockApiLock))

      override def showBlock(q: BlockQuery): Task[BlockQueryResponse] =
        defer(BlockAPI.showBlock[F](q))

      override def queryState(q: QueryStateRequest): Task[QueryStateResponse] = q match {
        case QueryStateRequest(blockHash, keyType, keyValue, path) =>
          val f = for {
            key <- toKey[F](keyType, keyValue)
            bq  <- BlockAPI.showBlock[F](BlockQuery(blockHash))
            state <- Concurrent[F]
                      .fromOption(bq.blockInfo, new Exception(s"Block $blockHash not found!"))
                      .map(_.globalStateRootHash)
            stateHash        = ByteString.copyFrom(Base16.decode(state))
            possibleResponse <- ExecutionEngineService[F].query(stateHash, key, splitPath(path))
            response         <- Concurrent[F].fromEither(possibleResponse).map(_.toProtoString)
          } yield QueryStateResponse(response)
          defer(f)
      }

      override def visualizeDag(q: VisualizeDagQuery): Task[VisualizeBlocksResponse] =
        Task.raiseError(
          Unimplemented(
            "Deprecated. Use CasperService.StreamBlockInfos and visualize on the client side."
          )
        )

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
    }

    BlockAPI.establishMetrics[F] *> Sync[F].delay(mkService)
  }
}
