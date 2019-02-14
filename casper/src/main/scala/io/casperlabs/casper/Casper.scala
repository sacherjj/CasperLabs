package io.casperlabs.casper

import cats.effect.concurrent.Semaphore
import cats.{Applicative, Monad}
import cats.implicits._
import cats.effect.{Concurrent, Sync}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{
  BlockDagRepresentation,
  BlockDagStorage,
  BlockMetadata,
  BlockStore
}
import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.casper.util.rholang._
import io.casperlabs.catscontrib._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.shared._
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicAny
import io.casperlabs.catscontrib.ski._

import scala.concurrent.SyncVar

trait Casper[F[_], A] {
  def addBlock(
      b: BlockMessage,
      handleDoppelganger: (BlockMessage, Validator) => F[Unit]
  ): F[BlockStatus]
  def contains(b: BlockMessage): F[Boolean]
  def deploy(d: DeployData): F[Either[Throwable, Unit]]
  def estimator(dag: BlockDagRepresentation[F]): F[A]
  def createBlock: F[CreateBlockStatus]
}

trait MultiParentCasper[F[_]] extends Casper[F, IndexedSeq[BlockMessage]] {
  def blockDag: F[BlockDagRepresentation[F]]
  def fetchDependencies: F[Unit]
  // This is the weight of faults that have been accumulated so far.
  // We want the clique oracle to give us a fault tolerance that is greater than
  // this initial fault weight combined with our fault tolerance threshold t.
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float]
  def lastFinalizedBlock: F[BlockMessage]
  def storageContents(hash: ByteString): F[String]
  // TODO: Refactor hashSetCasper to take a RuntimeManager[F] just like BlockStore[F]
  def getRuntimeManager: F[Option[RuntimeManager[F]]]
}

object MultiParentCasper extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance
  def ignoreDoppelgangerCheck[F[_]: Applicative]: (BlockMessage, Validator) => F[Unit] =
    kp2(().pure[F])

  def forkChoiceTip[F[_]: MultiParentCasper: Monad]: F[BlockMessage] =
    for {
      dag  <- MultiParentCasper[F].blockDag
      tips <- MultiParentCasper[F].estimator(dag)
      tip  = tips.head
    } yield tip
}

sealed abstract class MultiParentCasperInstances {

  def hashSetCasper[F[_]: Concurrent: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: BlockStore: RPConfAsk: BlockDagStorage](
      runtimeManager: RuntimeManager[F],
      validatorId: Option[ValidatorIdentity],
      genesis: BlockMessage,
      shardId: String
  ): F[MultiParentCasper[F]] =
    for {
      // Initialize DAG storage with genesis block in case it is empty
      _   <- BlockDagStorage[F].insert(genesis)
      dag <- BlockDagStorage[F].getRepresentation
      // TODO: bring back when validation is working again
      // maybePostGenesisStateHash <- InterpreterUtil
      //                               .validateBlockCheckpoint[F](
      //                                 genesis,
      //                                 dag,
      //                                 runtimeManager
      //                               )
      maybePostGenesisStateHash <- Sync[F]
                                    .pure[Either[BlockException, Option[StateHash]]](Right(None))
      postGenesisStateHash <- maybePostGenesisStateHash match {
                               case Left(BlockException(ex)) => Sync[F].raiseError[StateHash](ex)
                               case Right(None)              =>
                                 //todo when blessed contracts finished, this should be comment out.
//                                 Sync[F].raiseError[StateHash](
//                                   new Exception("Genesis tuplespace validation failed!")
//                                 )
                                 ByteString.copyFromUtf8("test").pure[F]
                               case Right(Some(hash)) => hash.pure[F]
                             }
      blockProcessingLock <- Semaphore[F](1)
      casperState <- Cell.mvarCell[F, CasperState](
                      CasperState()
                    )

    } yield {
      implicit val state = casperState
      implicit val ee    = runtimeManager.executionEngineService
      new MultiParentCasperImpl[F](
        validatorId,
        genesis,
        postGenesisStateHash,
        shardId,
        blockProcessingLock
      )
    }
}
