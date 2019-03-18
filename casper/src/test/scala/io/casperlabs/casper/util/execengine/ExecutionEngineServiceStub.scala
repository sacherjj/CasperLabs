package io.casperlabs.casper.util.execengine

import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.either._
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.Bond
import io.casperlabs.ipc._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

import scala.util.Either

object ExecutionEngineServiceStub {
  type Bonds = Map[Array[Byte], Long]

  def mock[F[_]](
      execFunc: (ByteString, Seq[Deploy]) => F[Either[Throwable, Seq[DeployResult]]],
      commitFunc: (ByteString, Seq[TransformEntry]) => F[Either[Throwable, ByteString]],
      queryFunc: (ByteString, Key, Seq[String]) => F[Either[Throwable, Value]],
      computeBondsFunc: ByteString => F[Seq[Bond]],
      setBondsFunc: Bonds => F[Unit],
      verifyWasmFunc: ValidateRequest => F[Either[String, Unit]]
  ): ExecutionEngineService[F] = new ExecutionEngineService[F] {
    override def emptyStateHash: ByteString = ByteString.EMPTY
    override def exec(
        prestate: ByteString,
        deploys: Seq[Deploy]
    ): F[Either[Throwable, Seq[DeployResult]]] =
      execFunc(prestate, deploys)
    override def commit(
        prestate: ByteString,
        effects: Seq[TransformEntry]
    ): F[Either[Throwable, ByteString]] = commitFunc(prestate, effects)
    override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
      computeBondsFunc(hash)
    override def setBonds(bonds: Map[Array[Byte], Long]): F[Unit] =
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
      (_, _) => Seq.empty[DeployResult].asRight[Throwable].pure,
      (_, _) => ByteString.EMPTY.asRight[Throwable].pure,
      (_, _, _) =>
        Applicative[F]
          .pure[Either[Throwable, Value]](Left(new SmartContractEngineError("unimplemented"))),
      _ => Seq.empty[Bond].pure,
      _ => Applicative[F].unit,
      _ => ().asRight[String].pure[F]
    )

}
