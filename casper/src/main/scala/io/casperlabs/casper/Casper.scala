package io.casperlabs.casper

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus._
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
  def estimator(dag: BlockDagRepresentation[F]): F[A]
  def createBlock: F[CreateBlockStatus]
  def bufferedDeploys: F[DeployBuffer]
}

case class DeployBuffer(
    // Deploys that have been processed at least once,
    // waiting to be finalized or orphaned.
    processedDeploys: Map[DeployHash, Deploy],
    // Deploys not yet included in a block.
    pendingDeploys: Map[DeployHash, Deploy]
) {
  def size =
    processedDeploys.size + pendingDeploys.size

  def add(deploy: Deploy) =
    if (!contains(deploy))
      copy(pendingDeploys = pendingDeploys + (deploy.deployHash -> deploy))
    else
      this

  // Removes deploys that were included in a finalized block.
  def remove(deployHashes: Set[DeployHash]) =
    copy(
      processedDeploys = processedDeploys.filterNot(kv => deployHashes(kv._1)),
      // They could be in pendingDeploys too if they were sent to multiple nodes.
      pendingDeploys = pendingDeploys.filterNot(kv => deployHashes(kv._1))
    )

  // Move some deploys from pending to processed.
  def processed(deployHashes: Set[DeployHash]) =
    copy(
      processedDeploys = processedDeploys ++ pendingDeploys.filter(kv => deployHashes(kv._1)),
      pendingDeploys = pendingDeploys.filterNot(kv => deployHashes(kv._1))
    )

  // Move some deploys back from processed to pending.
  def orphaned(deployHashes: Set[DeployHash]) =
    copy(
      processedDeploys = processedDeploys.filterNot(kv => deployHashes(kv._1)),
      pendingDeploys = pendingDeploys ++ processedDeploys.filter(kv => deployHashes(kv._1))
    )

  def get(deployHash: DeployHash): Option[Deploy] =
    pendingDeploys.get(deployHash) orElse processedDeploys.get(deployHash)

  def contains(deploy: Deploy) =
    pendingDeploys.contains(deploy.deployHash) || processedDeploys.contains(deploy.deployHash)
}
object DeployBuffer {
  val empty = DeployBuffer(Map.empty, Map.empty)
}

trait MultiParentCasper[F[_]] extends Casper[F, IndexedSeq[BlockHash]] {
  def blockDag: F[BlockDagRepresentation[F]]
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

  def forkChoiceTip[F[_]: MultiParentCasper: MonadThrowable: BlockStore]: F[Block] =
    for {
      dag       <- MultiParentCasper[F].blockDag
      tipHashes <- MultiParentCasper[F].estimator(dag)
      tipHash   = tipHashes.head
      tip       <- ProtoUtil.unsafeGetBlock[F](tipHash)
    } yield tip
}

sealed abstract class MultiParentCasperInstances {

  private def init[F[_]: Concurrent: Log: BlockStore: BlockDagStorage: ExecutionEngineService: Validation](
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

  def fromTransportLayer[F[_]: Concurrent: ConnectionsCell: TransportLayer: Log: Time: Metrics: ErrorHandler: FinalityDetector: BlockStore: RPConfAsk: BlockDagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: Validation](
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
  def fromGossipServices[F[_]: Concurrent: Log: Time: Metrics: FinalityDetector: BlockStore: BlockDagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: Validation](
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
