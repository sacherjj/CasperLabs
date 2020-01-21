package io.casperlabs.casper

import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.util.CasperLabsProtocol
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.ipc
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage, FinalityStorage}
import io.casperlabs.storage.deploy.DeployStorage

import scala.concurrent.duration.FiniteDuration

trait MultiParentCasper[F[_]] {
  //// Brought from Casper trait
  def addBlock(block: Block): F[BlockStatus]
  def contains(block: Block): F[Boolean]
  def estimator(
      dag: DagRepresentation[F],
      lfbHash: ByteString,
      latestMessages: Map[ByteString, Set[ByteString]],
      equivocators: Set[Validator]
  ): F[NonEmptyList[ByteString]]
  def createMessage(canCreateBallot: Boolean): F[CreateBlockStatus]
  def createBlock: F[CreateBlockStatus] = createMessage(false) // Left for the sake of unit tests.
  def dag: F[DagRepresentation[F]]
  def lastFinalizedBlock: F[Block]
}

object MultiParentCasper extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance
}

sealed abstract class MultiParentCasperInstances {

  private def init[F[_]: Concurrent: Log: BlockStorage: DagStorage: ExecutionEngineService: Validation: CasperLabsProtocol](
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap
  ) =
    for {
      _ <- Validation[F].transactions(genesis, genesisPreState, BlockEffects(genesisEffects))
      casperState <- Cell.mvarCell[F, CasperState](
                      CasperState()
                    )
    } yield casperState

  /** Create a MultiParentCasper instance from the new RPC style gossiping. */
  def fromGossipServices[F[_]: Concurrent: Log: Time: Metrics: BlockStorage: DagStorage: DeployBuffer: FinalityStorage: ExecutionEngineService: DeployStorage: Validation: DeploySelection: CasperLabsProtocol: EventEmitter](
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap,
      chainName: String,
      minTtl: FiniteDuration,
      upgrades: Seq[ipc.ChainSpec.UpgradePoint]
  ): F[MultiParentCasper[F]] =
    for {
      lfbRef <- FinalityStorage[F].getLastFinalizedBlock.flatMap(Ref.of(_))
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
                 minTtl,
                 upgrades,
                 lfbRef = lfbRef
               )
    } yield casper
}
