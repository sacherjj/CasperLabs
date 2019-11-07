package io.casperlabs.casper

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.util.CasperLabsProtocolVersions
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.validation.Validation
import io.casperlabs.comm.gossiping
import io.casperlabs.ipc
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Weight
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import io.casperlabs.storage.deploy.DeployStorage

trait MultiParentCasper[F[_]] {
  //// Brought from Casper trait
  def addBlock(block: Block): F[BlockStatus]
  def contains(block: Block): F[Boolean]
  def deploy(deployData: Deploy): F[Either[Throwable, Unit]]
  def estimator(
      dag: DagRepresentation[F],
      latestMessages: Map[ByteString, Set[ByteString]],
      equivocators: Set[Validator]
  ): F[List[ByteString]]
  def createBlock: F[CreateBlockStatus]
  ////

  def dag: F[DagRepresentation[F]]

  def lastFinalizedBlock: F[Block]
}

object MultiParentCasper extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance
}

sealed abstract class MultiParentCasperInstances {

  private def init[F[_]: Concurrent: Log: BlockStorage: DagStorage: ExecutionEngineService: Validation](
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap
  ) =
    for {
      _ <- Validation[F].transactions(genesis, genesisPreState, genesisEffects)
      casperState <- Cell.mvarCell[F, CasperState](
                      CasperState()
                    )
    } yield casperState

  /** Create a MultiParentCasper instance from the new RPC style gossiping. */
  def fromGossipServices[F[_]: Concurrent: Log: Time: Metrics: BlockStorage: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: DeployStorage: Validation: DeploySelection: CasperLabsProtocolVersions](
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap,
      chainName: String,
      upgrades: Seq[ipc.ChainSpec.UpgradePoint]
  ): F[MultiParentCasper[F]] =
    for {
      implicit0(casperState: Cell[F, CasperState]) <- init(
                                                       genesis,
                                                       genesisPreState,
                                                       genesisEffects
                                                     )
      semaphoreMap <- SemaphoreMap[F, ByteString](1)
      statelessExecutor <- MultiParentCasperImpl.StatelessExecutor
                            .create[F](validatorId.map(_.publicKey), chainName, upgrades)
      casper <- MultiParentCasperImpl.create[F](
                 semaphoreMap,
                 statelessExecutor,
                 validatorId,
                 genesis,
                 chainName,
                 upgrades
               )
    } yield casper
}
