package io.casperlabs.node.api

import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.info._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.consensus.{state, Block}
import io.casperlabs.casper.finality.singlesweep.FinalityDetector
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.comm.ServiceError.InvalidArgument
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.Utils.{validateBlockHashPrefix, validateDeployHash}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.node.api.casper._
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block._
import io.casperlabs.storage.deploy.DeployStorage
import monix.eval.{Task, TaskLike}
import monix.reactive.Observable

object GrpcCasperService {

  def apply[F[_]: Concurrent: TaskLike: Log: Metrics: MultiParentCasperRef: FinalityDetector: BlockStorage: ExecutionEngineService: DeployStorage: Validation: Fs2Compiler]()
      : F[CasperGrpcMonix.CasperService] =
    BlockAPI.establishMetrics[F] *> Sync[F].delay {
      val adaptToInvalidArgument: PartialFunction[Throwable, Throwable] = {
        case e => InvalidArgument(e.getMessage)
      }

      new CasperGrpcMonix.CasperService {
        override def deploy(request: DeployRequest): Task[Empty] =
          TaskLike[F].apply {
            BlockAPI.deploy[F](request.getDeploy).map(_ => Empty())
          }

        override def getBlockInfo(request: GetBlockInfoRequest): Task[BlockInfo] =
          TaskLike[F].apply {
            validateBlockHashPrefix[F](request.blockHashBase16, adaptToInvalidArgument) >>= {
              blockHashPrefix =>
                BlockAPI
                  .getBlockInfo[F](
                    blockHashPrefix
                  )
            }
          }

        override def streamBlockInfos(request: StreamBlockInfosRequest): Observable[BlockInfo] = {
          val infos = TaskLike[F].apply {
            BlockAPI.getBlockInfos[F](
              depth = request.depth,
              maxRank = request.maxRank
            )
          }
          Observable.fromTask(infos).flatMap(Observable.fromIterable)
        }

        override def getDeployInfo(request: GetDeployInfoRequest): Task[DeployInfo] =
          TaskLike[F].apply {
            validateDeployHash[F](request.deployHashBase16, adaptToInvalidArgument) >>= {
              deployHash =>
                implicit val v = request.view
                BlockAPI
                  .getDeployInfo[F](deployHash)
            }
          }

        override def streamBlockDeploys(
            request: StreamBlockDeploysRequest
        ): Observable[Block.ProcessedDeploy] = {
          val deploys = TaskLike[F].apply {
            validateBlockHashPrefix[F](request.blockHashBase16, adaptToInvalidArgument) >>= {
              blockHashPrefix =>
                implicit val v = request.view
                BlockAPI.getBlockDeploys[F](blockHashPrefix)
            }
          }
          Observable.fromTask(deploys).flatMap(Observable.fromIterable)
        }

        override def getBlockState(request: GetBlockStateRequest): Task[state.Value] =
          batchGetBlockState(
            BatchGetBlockStateRequest(request.blockHashBase16, List(request.getQuery))
          ) map {
            _.values.head
          }

        override def batchGetBlockState(
            request: BatchGetBlockStateRequest
        ): Task[BatchGetBlockStateResponse] = TaskLike[F].apply {
          for {
            blockHashPrefix <- validateBlockHashPrefix[F](
                                request.blockHashBase16,
                                adaptToInvalidArgument
                              )
            info            <- BlockAPI.getBlockInfo[F](blockHashPrefix)
            stateHash       = info.getSummary.state.postStateHash
            protocolVersion = info.getSummary.getHeader.getProtocolVersion
            values          <- request.queries.toList.traverse(getState(stateHash, _, protocolVersion))
          } yield BatchGetBlockStateResponse(values)
        }

        private def getState(
            stateHash: ByteString,
            query: StateQuery,
            protocolVersion: ProtocolVersion
        ): F[state.Value] =
          for {
            key <- toKey[F](query.keyVariant, query.keyBase16)
            possibleResponse <- ExecutionEngineService[F].query(
                                 stateHash,
                                 key,
                                 query.pathSegments,
                                 protocolVersion
                               )
            value <- Concurrent[F].fromEither(possibleResponse).handleErrorWith {
                      case SmartContractEngineError(msg) =>
                        MonadThrowable[F].raiseError(InvalidArgument(msg))
                    }
          } yield value
      }
    }

  def toKey[F[_]: MonadThrowable](
      keyType: StateQuery.KeyVariant,
      keyValue: String
  ): F[state.Key] =
    Utils.toKey[F](keyType.name, keyValue).handleErrorWith {
      case ex: java.lang.IllegalArgumentException =>
        MonadThrowable[F].raiseError(InvalidArgument(ex.getMessage))
    }
}
