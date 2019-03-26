package io.casperlabs.node.api

import cats.{ApplicativeError, Id}
import cats.data.StateT
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import cats.mtl._
import cats.mtl.implicits._
import com.google.protobuf.empty.Empty
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.{BlockAPI, GraphConfig, GraphzGenerator}
import io.casperlabs.casper.protocol.{DeployData, DeployServiceResponse, _}
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.graphz.{GraphSerializer, Graphz, StringSerializer}
import io.casperlabs.ipc
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler
import monix.reactive.Observable
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.smartcontracts.ExecutionEngineService

private[api] object DeployGrpcService {
  def toKey[F[_]](keyType: String, keyValue: String)(
      implicit appErr: ApplicativeError[F, Throwable]
  ): F[ipc.Key] = {
    val keyBytes = ByteString.copyFrom(Base16.decode(keyValue))
    keyType.toLowerCase match {
      case "hash" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Hash(ipc.KeyHash(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new Exception(
                s"Key of type hash must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "uref" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Uref(ipc.KeyURef(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new Exception(
                s"Key of type uref must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "address" =>
        keyBytes.size match {
          case 20 => ipc.Key(ipc.Key.KeyInstance.Account(ipc.KeyAddress(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new Exception(
                s"Key of type address must have exactly 20 bytes, $n =/= 20 provided."
              )
            )
        }
      case _ =>
        appErr.raiseError(
          new Exception(
            s"Key variant $keyType not valid. Must be one of hash, uref, address."
          )
        )
    }
  }

  def splitPath(path: String): Seq[String] =
    path.split("/").filter(_.nonEmpty)

  def instance[F[_]: Concurrent: MultiParentCasperRef: Log: Metrics: SafetyOracle: BlockStore: TaskLike: ExecutionEngineService](
      blockApiLock: Semaphore[F]
  )(
      implicit worker: Scheduler
  ): F[CasperMessageGrpcMonix.DeployService] = {
    def mkService = new CasperMessageGrpcMonix.DeployService {
      private def defer[A](task: F[A]): Task[A] =
        Task.defer(TaskLike[F].toTask(task)).executeOn(worker).attemptAndLog

      override def doDeploy(d: DeployData): Task[DeployServiceResponse] =
        defer(BlockAPI.deploy[F](d))

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
                      .map(_.tupleSpaceHash)
            stateHash        = ByteString.copyFrom(Base16.decode(state))
            possibleResponse <- ExecutionEngineService[F].query(stateHash, key, splitPath(path))
            response         <- Concurrent[F].fromEither(possibleResponse).map(_.toProtoString)
          } yield QueryStateResponse(response)
          defer(f)
      }

      // TODO handle potentiall errors (at least by returning proper response)
      override def visualizeDag(q: VisualizeDagQuery): Task[VisualizeBlocksResponse] = {
        type Effect[A] = StateT[Id, StringBuffer, A]
        implicit val ser: GraphSerializer[Effect]       = new StringSerializer[Effect]
        val stringify: Effect[Graphz[Effect]] => String = _.runS(new StringBuffer).toString

        val depth  = if (q.depth <= 0) None else Some(q.depth)
        val config = GraphConfig(q.showJustificationLines)

        defer(
          BlockAPI
            .visualizeDag[F, Effect](
              depth,
              (ts, lfb) => GraphzGenerator.dagAsCluster[F, Effect](ts, lfb, config),
              stringify
            )
            .map(graph => VisualizeBlocksResponse(graph))
        )
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
    }

    BlockAPI.establishMetrics[F] *> Sync[F].delay(mkService)
  }
}
