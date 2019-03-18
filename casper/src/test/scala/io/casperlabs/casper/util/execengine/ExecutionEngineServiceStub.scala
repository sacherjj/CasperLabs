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
      exec: (ByteString, Seq[Deploy]) => F[Either[Throwable, Seq[DeployResult]]],
      commit: (ByteString, Seq[TransformEntry]) => F[Either[Throwable, ByteString]],
      query: (ByteString, Key, Seq[String]) => F[Either[Throwable, Value]],
      computeBonds: ByteString => F[Seq[Bond]],
      setBonds: Bonds => F[Unit],
      veriyWasm: ValidateRequest => F[Either[String, Unit]]
  ): ExecutionEngineService[F] = new ExecutionEngineService[F] {
    override def emptyStateHash: ByteString = ByteString.EMPTY
    override def exec(
        prestate: ByteString,
        deploys: Seq[Deploy]
    ): F[Either[Throwable, Seq[DeployResult]]] =
      exec(prestate, deploys)
    override def commit(
        prestate: ByteString,
        effects: Seq[TransformEntry]
    ): F[Either[Throwable, ByteString]] = commit(prestate, effects)
    override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
      computeBonds(hash)
    override def setBonds(bonds: Map[Array[Byte], Long]): F[Unit] =
      setBonds(bonds)
    override def query(
        state: ByteString,
        baseKey: Key,
        path: Seq[String]
    ): F[Either[Throwable, Value]] = query(state, baseKey, path)
    override def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]] =
      verifyWasm(contracts)
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
