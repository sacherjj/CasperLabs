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
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.node.api.casper._
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block._
import io.casperlabs.storage.deploy.{DeployStorageReader, DeployStorageWriter}
import monix.eval.{Task, TaskLike}
import monix.reactive.Observable

object GrpcCasperService {

  def apply[F[_]: Concurrent: TaskLike: Log: Metrics: MultiParentCasperRef: FinalityDetector: BlockStorage: ExecutionEngineService: DeployStorageReader: DeployStorageWriter: Validation: Fs2Compiler]()
      : F[CasperGrpcMonix.CasperService] =
    BlockAPI.establishMetrics[F] *> Sync[F].delay {
      def validateBlockHashPrefix(p: String): F[String] =
        Utils
          .checkString[F](
            p,
            "BlockHash prefix must be at least 4 characters (2 bytes) long",
            s => Base16.tryDecode(s).exists(_.length >= 2)
          )
          .adaptErr {
            case e => InvalidArgument(e.getMessage)
          }

      def validateDeployHash(p: String): F[String] =
        Utils
          .checkString[F](
            p,
            "DeployHash must be 64 characters (32 bytes) long",
            Base16.tryDecode(_).exists(_.length == 32)
          )
          .adaptErr {
            case e => InvalidArgument(e.getMessage)
          }

      new CasperGrpcMonix.CasperService {
        override def deploy(request: DeployRequest): Task[Empty] =
          TaskLike[F].apply {
            BlockAPI.deploy[F](request.getDeploy).map(_ => Empty())
          }

        override def getBlockInfo(request: GetBlockInfoRequest): Task[BlockInfo] =
          TaskLike[F].apply {
            validateBlockHashPrefix(request.blockHashBase16) >>= { blockHashPrefix =>
              BlockAPI
                .getBlockInfo[F](
                  blockHashPrefix,
                  full = request.view == BlockInfo.View.FULL
                )
            }
          }

        override def streamBlockInfos(request: StreamBlockInfosRequest): Observable[BlockInfo] = {
          val infos = TaskLike[F].apply {
            BlockAPI.getBlockInfos[F](
              depth = request.depth,
              maxRank = request.maxRank,
              full = request.view == BlockInfo.View.FULL
            )
          }
          Observable.fromTask(infos).flatMap(Observable.fromIterable)
        }

        override def getDeployInfo(request: GetDeployInfoRequest): Task[DeployInfo] =
          TaskLike[F].apply {
            validateDeployHash(request.deployHashBase16) >>= { deployHash =>
              BlockAPI
                .getDeployInfo[F](deployHash) map { info =>
                request.view match {
                  case DeployInfo.View.BASIC =>
                    info.withDeploy(info.getDeploy.copy(body = None))
                  case _ =>
                    info
                }
              }
            }
          }

        override def streamBlockDeploys(
            request: StreamBlockDeploysRequest
        ): Observable[Block.ProcessedDeploy] = {
          val deploys = TaskLike[F].apply {
            validateBlockHashPrefix(request.blockHashBase16) >>= { blockHashPrefix =>
              BlockAPI.getBlockDeploys[F](blockHashPrefix) map {
                _ map { pd =>
                  request.view match {
                    case DeployInfo.View.BASIC =>
                      pd.withDeploy(pd.getDeploy.copy(body = None))
                    case _ =>
                      pd
                  }
                }
              }
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
            blockHashPrefix <- validateBlockHashPrefix(request.blockHashBase16)
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
