package io.casperlabs.casper.util.execengine

import cats.Applicative
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.Bond
import io.casperlabs.ipc._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import cats.syntax.either._
import cats.syntax.applicative._

import scala.util.Either

object ExecutionEngineServiceStub {
  def noOpApi[F[_]: Applicative](): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      override def emptyStateHash: ByteString = ByteString.EMPTY
      override def exec(
          prestate: ByteString,
          deploys: Seq[Deploy]
      ): F[Either[Throwable, Seq[DeployResult]]] =
        Seq.empty[DeployResult].asRight[Throwable].pure
      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = ByteString.EMPTY.asRight[Throwable].pure
      override def query(
          state: ByteString,
          baseKey: Key,
          path: Seq[String]
      ): F[Either[Throwable, Value]] =
        Applicative[F]
          .pure[Either[Throwable, Value]](Left(new SmartContractEngineError("unimplemented")))
      override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
        Seq.empty[Bond].pure
      override def setBonds(bonds: Map[Array[Byte], Long]): F[Unit] = Applicative[F].unit
      override def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]] =
        ().asRight[String].pure[F]
    }

}
