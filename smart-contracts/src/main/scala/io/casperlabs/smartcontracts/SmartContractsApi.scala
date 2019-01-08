package io.casperlabs.smartcontracts
import java.nio.file.Path

import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.{Bond, Deploy}
import io.casperlabs.ipc.{CommutativeEffects, Done, ExecutionEffect, Deploy => EEDeploy}
import io.casperlabs.models.{Failed, InternalProcessedDeploy, Succeeded}
import io.casperlabs.shared.StoreType
import io.casperlabs.smartcontracts.ExecutionEngineService
import simulacrum.typeclass

@typeclass trait SmartContractsApi[F[_]] {
  def sendDeploy(d: EEDeploy): F[Either[Throwable, ExecutionEffect]]

  def executeEffects(c: CommutativeEffects): F[Either[Throwable, Done]]

  def close(): F[Unit]
}

object SmartContractsApi {
  def of[F[_]: Applicative: ExecutionEngineService](
      storagePath: Path,
      storageSize: Long,
      storeType: StoreType
  ): SmartContractsApi[F] =
    new SmartContractsApi[F] {

      override def close(): F[Unit] =
        ().pure

      override def executeEffects(c: CommutativeEffects): F[Either[Throwable, Done]] =
        ExecutionEngineService[F].executeEffects(c)

      override def sendDeploy(d: EEDeploy): F[Either[Throwable, ExecutionEffect]] =
        ExecutionEngineService[F].sendDeploy(d)
    }
}
