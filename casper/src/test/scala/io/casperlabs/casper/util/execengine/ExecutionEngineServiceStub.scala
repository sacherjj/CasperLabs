package io.casperlabs.casper.util.execengine

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.{Validation, ValidationImpl}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.ipc._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService

import scala.concurrent.duration.FiniteDuration
import scala.util.Either

object ExecutionEngineServiceStub {
  type Bonds = Map[PublicKey, Long]

  implicit def functorRaiseInvalidBlock[F[_]: Sync] =
    ValidationImpl.raiseValidateErrorThroughSync[F]

  def validateBlockCheckpoint[F[_]: Sync: Log: BlockStore: ExecutionEngineService](
      b: Block,
      dag: BlockDagRepresentation[F]
  ): F[Either[Throwable, StateHash]] = {
    implicit val time = new Time[F] {
      override def currentMillis: F[Long]                   = 0L.pure[F]
      override def nanoTime: F[Long]                        = 0L.pure[F]
      override def sleep(duration: FiniteDuration): F[Unit] = Sync[F].unit
    }
    implicit val validation = new ValidationImpl[F]
    (for {
      parents      <- ProtoUtil.unsafeGetParents[F](b)
      merged       <- ExecEngineUtil.merge[F](parents, dag)
      preStateHash <- ExecEngineUtil.computePrestate[F](merged)
      effects      <- ExecEngineUtil.effectsForBlock[F](b, preStateHash, dag)
      _            <- Validation[F].transactions(b, dag, preStateHash, effects)
    } yield ProtoUtil.postStateHash(b)).attempt
  }

  def mock[F[_]](
      execFunc: (
          ByteString,
          Seq[Deploy],
          ProtocolVersion
      ) => F[Either[Throwable, Seq[DeployResult]]],
      commitFunc: (ByteString, Seq[TransformEntry]) => F[Either[Throwable, ByteString]],
      queryFunc: (ByteString, Key, Seq[String]) => F[Either[Throwable, Value]],
      computeBondsFunc: ByteString => F[Seq[Bond]],
      setBondsFunc: Bonds => F[Unit],
      verifyWasmFunc: ValidateRequest => F[Either[String, Unit]]
  ): ExecutionEngineService[F] = new ExecutionEngineService[F] {
    override def emptyStateHash: ByteString = ByteString.EMPTY
    override def exec(
        prestate: ByteString,
        deploys: Seq[Deploy],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, Seq[DeployResult]]] =
      execFunc(prestate, deploys, protocolVersion)
    override def commit(
        prestate: ByteString,
        effects: Seq[TransformEntry]
    ): F[Either[Throwable, ByteString]] = commitFunc(prestate, effects)
    override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
      computeBondsFunc(hash)
    override def setBonds(bonds: Map[PublicKey, Long]): F[Unit] =
      setBondsFunc(bonds)
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
      (_, _, _) => Seq.empty[DeployResult].asRight[Throwable].pure[F],
      (_, _) => ByteString.EMPTY.asRight[Throwable].pure[F],
      (_, _, _) =>
        Applicative[F]
          .pure[Either[Throwable, Value]](Left(new SmartContractEngineError("unimplemented"))),
      _ => Seq.empty[Bond].pure[F],
      _ => Applicative[F].unit,
      _ => ().asRight[String].pure[F]
    )

}
