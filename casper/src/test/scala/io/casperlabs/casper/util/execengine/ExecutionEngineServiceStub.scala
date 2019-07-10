package io.casperlabs.casper.util.execengine

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.{InvalidBlock, Validate}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.ipc._
import io.casperlabs.casper.consensus.state.{Unit => SUnit, _}
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

import scala.util.Either

object ExecutionEngineServiceStub {
  type Bonds = Map[PublicKey, Long]

  implicit def functorRaiseInvalidBlock[F[_]: Sync]: FunctorRaise[F, InvalidBlock] =
    Validate.raiseValidateErrorThroughApplicativeError[F]

  def validateBlockCheckpoint[F[_]: Sync: Log: BlockStore: ExecutionEngineService](
      b: Block,
      dag: BlockDagRepresentation[F]
  ): F[Either[Throwable, StateHash]] =
    (for {
      parents      <- ProtoUtil.unsafeGetParents[F](b)
      merged       <- ExecEngineUtil.merge[F](parents, dag)
      preStateHash <- ExecEngineUtil.computePrestate[F](merged)
      effects      <- ExecEngineUtil.effectsForBlock[F](b, preStateHash)
      _            <- Validate.transactions[F](b, preStateHash, effects)
    } yield ProtoUtil.postStateHash(b)).attempt

  def mock[F[_]](
      runGenesisFunc: (
          Seq[Deploy],
          ProtocolVersion
      ) => F[Either[Throwable, GenesisResult]],
      execFunc: (
          ByteString,
          Long,
          Seq[Deploy],
          ProtocolVersion
      ) => F[Either[Throwable, Seq[DeployResult]]],
      commitFunc: (
          ByteString,
          Seq[TransformEntry]
      ) => F[Either[Throwable, ExecutionEngineService.CommitResult]],
      queryFunc: (ByteString, Key, Seq[String]) => F[Either[Throwable, Value]],
      verifyWasmFunc: ValidateRequest => F[Either[String, Unit]]
  ): ExecutionEngineService[F] = new ExecutionEngineService[F] {
    override def emptyStateHash: ByteString = ByteString.EMPTY
    override def runGenesis(
        deploys: Seq[Deploy],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, GenesisResult]] =
      runGenesisFunc(deploys, protocolVersion)
    override def exec(
        prestate: ByteString,
        blocktime: Long,
        deploys: Seq[Deploy],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, Seq[DeployResult]]] =
      execFunc(prestate, blocktime, deploys, protocolVersion)
    override def commit(
        prestate: ByteString,
        effects: Seq[TransformEntry]
    ): F[Either[Throwable, ExecutionEngineService.CommitResult]] = commitFunc(prestate, effects)

    override def query(
        state: ByteString,
        baseKey: Key,
        path: Seq[String]
    ): F[Either[Throwable, Value]] = queryFunc(state, baseKey, path)
    override def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]] =
      verifyWasmFunc(contracts)
  }

  def noOpApi[F[_]: Applicative](): ExecutionEngineService[F] =
    mock[F](
      (_, _) => GenesisResult().asRight[Throwable].pure[F],
      (_, _, _, _) => Seq.empty[DeployResult].asRight[Throwable].pure[F],
      (_, _) =>
        ExecutionEngineService
          .CommitResult(ByteString.EMPTY, Seq.empty[Bond])
          .asRight[Throwable]
          .pure[F],
      (_, _, _) =>
        Applicative[F]
          .pure[Either[Throwable, Value]](Left(new SmartContractEngineError("unimplemented"))),
      _ => ().asRight[String].pure[F]
    )

}
