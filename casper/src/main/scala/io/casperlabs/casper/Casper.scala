package io.casperlabs.casper

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.catscontrib.ski._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.comm.gossiping
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
    processedDeploys: Map[ByteString, Deploy],
    // Deploys we never looked at.
    newDeploys: Map[ByteString, Deploy]
) {
  def size =
    processedDeploys.size + newDeploys.size

  def add(deploy: Deploy) =
    if (!contains(deploy))
      copy(newDeploys = newDeploys + (deploy.deployHash -> deploy))
    else
      this

  // Removes deploys that were included in a block.
  // They could be in newDeploys too if they were sent to multiple nodes.
  def remove(deployHashes: Set[ByteString]) =
    copy(
      processedDeploys = processedDeploys.filterKeys(h => !deployHashes(h)),
      newDeploys = newDeploys.filterKeys(h => !deployHashes(h))
    )

  // Move some deploys from new to processed.
  def processed(deployHashes: Set[ByteString]) =
    copy(
      processedDeploys = processedDeploys ++ newDeploys.filterKeys(deployHashes),
      newDeploys = newDeploys.filterKeys(h => !deployHashes(h))
    )

  def get(deployHash: ByteString): Option[Deploy] =
    newDeploys.get(deployHash) orElse processedDeploys.get(deployHash)

  def contains(deploy: Deploy) =
    newDeploys.contains(deploy.deployHash) || processedDeploys.contains(deploy.deployHash)
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
  def faultToleranceThreshold: Float = 0f
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

  private def init[F[_]: Concurrent: Log: BlockStore: BlockDagStorage: ExecutionEngineService](
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap
  ) =
    for {
      dag <- BlockDagStorage[F].getRepresentation
      _ <- {
        implicit val functorRaiseInvalidBlock = Validate.raiseValidateErrorThroughSync[F]
        Validate.transactions[F](genesis, dag, genesisPreState, genesisEffects)
      }
      blockProcessingLock <- Semaphore[F](1)
      casperState <- Cell.mvarCell[F, CasperState](
                      CasperState()
                    )

    } yield (blockProcessingLock, casperState)

  def fromTransportLayer[F[_]: Concurrent: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: BlockStore: RPConfAsk: BlockDagStorage: ExecutionEngineService](
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap,
      shardId: String
  ): F[MultiParentCasper[F]] =
    init(genesis, genesisPreState, genesisEffects) map {
      case (blockProcessingLock, casperState) =>
        implicit val state = casperState
        new MultiParentCasperImpl[F](
          new MultiParentCasperImpl.StatelessExecutor(shardId),
          MultiParentCasperImpl.Broadcaster.fromTransportLayer(),
          validatorId,
          genesis,
          shardId,
          blockProcessingLock
        )
    }

  /** Create a MultiParentCasper instance from the new RPC style gossiping. */
  def fromGossipServices[F[_]: Concurrent: Log: Time: SafetyOracle: BlockStore: BlockDagStorage: ExecutionEngineService](
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap,
      shardId: String,
      relaying: gossiping.Relaying[F]
  ): F[MultiParentCasper[F]] =
    init(genesis, genesisPreState, genesisEffects) map {
      case (blockProcessingLock, casperState) =>
        implicit val state = casperState
        new MultiParentCasperImpl[F](
          new MultiParentCasperImpl.StatelessExecutor(shardId),
          MultiParentCasperImpl.Broadcaster.fromGossipServices(validatorId, relaying),
          validatorId,
          genesis,
          shardId,
          blockProcessingLock
        )
    }
}
