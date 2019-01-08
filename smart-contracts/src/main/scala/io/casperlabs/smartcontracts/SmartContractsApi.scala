package io.casperlabs.smartcontracts
import java.nio.file.Path

import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.{Bond, Deploy}
import io.casperlabs.ipc.{Deploy => EEDeploy}
import io.casperlabs.ipc.{CommutativeEffects, ExecutionEffect}
import io.casperlabs.models.{Failed, InternalProcessedDeploy, Succeeded}
import io.casperlabs.shared.StoreType
import io.casperlabs.smartcontracts.ExecutionEngineService
import simulacrum.typeclass

@typeclass trait SmartContractsApi[F[_]] {
  def sendDeploy(d: EEDeploy): F[Either[Throwable, ExecutionEffect]]

  def newEval(
      terms: Seq[(Deploy, ExecutionEffect)],
      initHash: ByteString,
      time: Option[Long] = None
  ): F[(ByteString, Seq[InternalProcessedDeploy])]

  def replayEval(
      terms: Seq[InternalProcessedDeploy],
      initHash: ByteString,
      time: Option[Long] = None
  ): F[Either[(Option[Deploy], Failed), ByteString]]

  def computeBonds(hash: ByteString): F[Bond]

  def close(): F[Unit]
}

object SmartContractsApi {
  def noOpApi[F[_]: Applicative: ExecutionEngineService](
      storagePath: Path,
      storageSize: Long,
      storeType: StoreType
  ): SmartContractsApi[F] =
    new SmartContractsApi[F] {
      override def newEval(
          terms: Seq[(Deploy, ExecutionEffect)],
          initHash: ByteString,
          time: Option[Long] = None
      ): F[(ByteString, Seq[InternalProcessedDeploy])] = {
        // todo using maximum commutative rules
        val commutativeEffects = CommutativeEffects(terms.flatMap(_._2.transformMap))
        for {
          d <- ExecutionEngineService[F].executeEffects(commutativeEffects)
        } yield
          d match {
            case Right(_) =>
              (initHash, terms.map(it => InternalProcessedDeploy(it._1, 0, Succeeded)))
            case Left(err) =>
              throw new IllegalArgumentException(
                s"Failed to execute effects: $err"
              )
          }
      }

      override def replayEval(
          terms: Seq[InternalProcessedDeploy],
          initHash: ByteString,
          time: Option[Long] = None
      ): F[Either[(Option[Deploy], Failed), ByteString]] =
        ByteString.EMPTY.asRight[(Option[Deploy], Failed)].pure
      override def close(): F[Unit] =
        ().pure
      override def computeBonds(hash: ByteString): F[Bond] =
        Bond().pure

      override def sendDeploy(d: EEDeploy): F[Either[Throwable, ExecutionEffect]] =
        ExecutionEngineService[F].sendDeploy(d)
    }
}
