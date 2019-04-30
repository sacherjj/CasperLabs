package io.casperlabs.casper

import cats.effect.concurrent.Semaphore
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService

trait Casper[F[_], A] {
  def addBlock(
      block: BlockMessage,
      handleDoppelganger: (BlockMessage, Validator) => F[Unit]
  ): F[BlockStatus]
  def contains(block: BlockMessage): F[Boolean]
  def deploy(deployData: DeployData): F[Either[Throwable, Unit]]
  def estimator(dag: BlockDagRepresentation[F]): F[A]
  def createBlock: F[CreateBlockStatus]
}

trait MultiParentCasper[F[_]] extends Casper[F, IndexedSeq[BlockHash]] {
  def blockDag: F[BlockDagRepresentation[F]]
  def fetchDependencies: F[Unit]
  // This is the weight of faults that have been accumulated so far.
  // We want the clique oracle to give us a fault tolerance that is greater than
  // this initial fault weight combined with our fault tolerance threshold t.
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float]
  def lastFinalizedBlock: F[BlockMessage]
  def storageContents(hash: ByteString): F[String]
}

object MultiParentCasper extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance
  def ignoreDoppelgangerCheck[F[_]: Applicative]: (BlockMessage, Validator) => F[Unit] =
    kp2(().pure[F])

  def forkChoiceTip[F[_]: MultiParentCasper: Monad: BlockStore]: F[BlockMessage] =
    for {
      dag       <- MultiParentCasper[F].blockDag
      tipHashes <- MultiParentCasper[F].estimator(dag)
      tipHash   = tipHashes.head
      tip       <- ProtoUtil.unsafeGetBlock[F](tipHash)
    } yield tip
}

sealed abstract class MultiParentCasperInstances {

  def hashSetCasper[F[_]: Concurrent: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: BlockStore: RPConfAsk: BlockDagStorage: ExecutionEngineService](
      validatorId: Option[ValidatorIdentity],
      genesis: BlockMessage,
      shardId: String
  ): F[MultiParentCasper[F]] =
    for {
      // Initialize DAG storage with genesis block in case it is empty
      _                   <- BlockDagStorage[F].insert(genesis)
      dag                 <- BlockDagStorage[F].getRepresentation
      _                   <- Sync[F].rethrow(ExecEngineUtil.validateBlockCheckpoint[F](genesis, dag))
      blockProcessingLock <- Semaphore[F](1)
      casperState <- Cell.mvarCell[F, CasperState](
                      CasperState()
                    )

    } yield {
      implicit val state = casperState
      new MultiParentCasperImpl[F](
        new MultiParentCasperImpl.StatelessExecutor(shardId),
        new MultiParentCasperImpl.Broadcaster(),
        validatorId,
        genesis,
        shardId,
        blockProcessingLock
      )
    }
}
