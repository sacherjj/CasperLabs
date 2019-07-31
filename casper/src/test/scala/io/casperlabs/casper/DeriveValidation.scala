package io.casperlabs.casper

import cats.MonadError
import cats.mtl.FunctorRaise
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.state.Value
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion}
import io.casperlabs.casper.consensus.{state, BlockSummary, Bond}
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.casper.validation.ValidationImpl
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.ipc.{Deploy, DeployResult, GenesisResult, TransformEntry, ValidateRequest}
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task

trait DeriveValidationLowPriority2 {
  //Should not reach these implementations
  def emptyEE[F[_]] = new ExecutionEngineService[F] {
    override def emptyStateHash: DeployHash = ???
    override def runGenesis(
        deploys: Seq[Deploy],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, GenesisResult]] = ???
    override def exec(
        prestate: DeployHash,
        blocktime: Long,
        deploys: Seq[Deploy],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, Seq[DeployResult]]] = ???
    override def commit(
        prestate: DeployHash,
        effects: Seq[TransformEntry]
    ): F[Either[Throwable, ExecutionEngineService.CommitResult]] = ???
    override def query(
        state: DeployHash,
        baseKey: Key,
        path: Seq[String]
    ): F[Either[Throwable, Value]]                                               = ???
    override def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]] = ???
  }
  def emptyBS[F[_]] = new BlockStore[F] {
    override def get(blockHash: BlockHash): F[Option[BlockMsgWithTransform]]  = ???
    override def findBlockHash(p: BlockHash => Boolean): F[Option[BlockHash]] = ???
    override def put(blockHash: BlockHash, blockMsgWithTransform: BlockMsgWithTransform): F[Unit] =
      ???
    override def getApprovedBlock(): F[Option[ApprovedBlock]]                             = ???
    override def putApprovedBlock(block: ApprovedBlock): F[Unit]                          = ???
    override def getBlockSummary(blockHash: BlockHash): F[Option[BlockSummary]]           = ???
    override def findBlockHashesWithDeployhash(deployHash: DeployHash): F[Seq[BlockHash]] = ???
    override def checkpoint(): F[Unit]                                                    = ???
    override def clear(): F[Unit]                                                         = ???
    override def close(): F[Unit]                                                         = ???
  }

  implicit def deriveValidationImplWithoutEEAndBS[F[_]](
      implicit
      fr: FunctorRaise[F, InvalidBlock],
      mt: MonadError[F, Throwable],
      time: Time[F],
      log: Log[F]
  ) = {
    implicit val ee = emptyEE[F]
    implicit val bs = emptyBS[F]
    new ValidationImpl[F]
  }
}

trait DeriveValidationLowPriority1 extends DeriveValidationLowPriority2 {
  implicit def deriveValidationImplWithoutEE[F[_]](
      implicit
      bs: BlockStore[F],
      fr: FunctorRaise[F, InvalidBlock],
      mt: MonadError[F, Throwable],
      time: Time[F],
      log: Log[F]
  ) = {
    implicit val ee = emptyEE[F]
    new ValidationImpl[F]
  }
}

object DeriveValidation extends DeriveValidationLowPriority1 {
  implicit def deriveValidationImpl[F[_]](
      implicit
      ee: ExecutionEngineService[F],
      bs: BlockStore[F],
      fr: FunctorRaise[F, InvalidBlock],
      log: Log[F],
      mt: MonadError[F, Throwable],
      time: Time[F]
  ) = new ValidationImpl[F]
}
