package io.casperlabs.node.api

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.{ApplicativeError, Id}
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.{BlockAPI}
import io.casperlabs.casper.protocol.{DeployData, DeployServiceResponse, _}
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.comm.ServiceError.Unimplemented
import io.casperlabs.ipc
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import java.lang.IllegalArgumentException
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler
import monix.reactive.Observable

object GrpcDeployService {
  def toKey[F[_]](keyType: String, keyBytes: ByteString)(
      implicit appErr: ApplicativeError[F, Throwable]
  ): F[ipc.Key] =
    keyType.toLowerCase match {
      case "hash" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Hash(ipc.KeyHash(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type hash must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "uref" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Uref(ipc.KeyURef(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type uref must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "address" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Account(ipc.KeyAddress(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type address must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case _ =>
        appErr.raiseError(
          new IllegalArgumentException(
            s"Key variant $keyType not valid. Must be one of hash, uref, address."
          )
        )
    }

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
            key <- toKey[F](keyType, ByteString.copyFrom(Base16.decode(keyValue)))
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
