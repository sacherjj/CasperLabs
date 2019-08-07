package io.casperlabs.casper

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import io.casperlabs.blockstorage.{BlockStorage, DagRepresentation, DagStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.deploybuffer.DeployBuffer
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.gossiping
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService

trait Casper[F[_], A] {
  def addBlock(block: Block): F[BlockStatus]
  def contains(block: Block): F[Boolean]
  def deploy(deployData: Deploy): F[Either[Throwable, Unit]]
  def estimator(dag: DagRepresentation[F]): F[A]
  def createBlock: F[CreateBlockStatus]
}

trait MultiParentCasper[F[_]] extends Casper[F, IndexedSeq[BlockHash]] {
  def dag: F[DagRepresentation[F]]
  def fetchDependencies: F[Unit]
  // This is the weight of faults that have been accumulated so far.
  // We want the clique oracle to give us a fault tolerance that is greater than
  // this initial fault weight combined with our fault tolerance threshold t.
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float]
  def lastFinalizedBlock: F[Block]
  def faultToleranceThreshold: Float
}

object MultiParentCasper extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance

  def forkChoiceTip[F[_]: MultiParentCasper: MonadThrowable: BlockStorage]: F[Block] =
    for {
      dag       <- MultiParentCasper[F].dag
      tipHashes <- MultiParentCasper[F].estimator(dag)
      tipHash   = tipHashes.head
      tip       <- ProtoUtil.unsafeGetBlock[F](tipHash)
    } yield tip
}

sealed abstract class MultiParentCasperInstances {

  private def init[F[_]: Concurrent: Log: BlockStorage: DagStorage: ExecutionEngineService: Validation](
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap
  ) =
    for {
      _                   <- Validation[F].transactions(genesis, genesisPreState, genesisEffects)
      blockProcessingLock <- Semaphore[F](1)
      casperState <- Cell.mvarCell[F, CasperState](
                      CasperState()
                    )
    } yield (blockProcessingLock, casperState)

  def fromTransportLayer[F[_]: Concurrent: ConnectionsCell: TransportLayer: Log: Time: Metrics: ErrorHandler: FinalityDetector: BlockStorage: RPConfAsk: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: DeployBuffer: Validation](
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap,
      chainId: String
  ): F[MultiParentCasper[F]] =
    for {
      (blockProcessingLock, implicit0(casperState: Cell[F, CasperState])) <- init(
                                                                              genesis,
                                                                              genesisPreState,
                                                                              genesisEffects
                                                                            )
      statelessExecutor <- MultiParentCasperImpl.StatelessExecutor.create[F](chainId)
      casper <- MultiParentCasperImpl.create[F](
                 statelessExecutor,
                 MultiParentCasperImpl.Broadcaster.fromTransportLayer(),
                 validatorId,
                 genesis,
                 chainId,
                 blockProcessingLock
               )
    } yield casper

  /** Create a MultiParentCasper instance from the new RPC style gossiping. */
  def fromGossipServices[F[_]: Concurrent: Log: Time: Metrics: FinalityDetector: BlockStorage: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: DeployBuffer: Validation](
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap,
      chainId: String,
      relaying: gossiping.Relaying[F]
  ): F[MultiParentCasper[F]] =
    for {
      (blockProcessingLock, implicit0(casperState: Cell[F, CasperState])) <- init(
                                                                              genesis,
                                                                              genesisPreState,
                                                                              genesisEffects
                                                                            )
      statelessExecutor <- MultiParentCasperImpl.StatelessExecutor.create[F](chainId)
      casper <- MultiParentCasperImpl.create[F](
                 statelessExecutor,
                 MultiParentCasperImpl.Broadcaster.fromGossipServices(validatorId, relaying),
                 validatorId,
                 genesis,
                 chainId,
                 blockProcessingLock
               )
    } yield casper
}
