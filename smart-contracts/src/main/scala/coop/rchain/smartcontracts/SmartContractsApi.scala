package coop.rchain.smartcontracts
import java.nio.file.Path

import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.either._
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol.{Bond, Deploy}
import coop.rchain.models.{Failed, InternalProcessedDeploy}
import coop.rchain.shared.StoreType
import simulacrum.typeclass

//TODO: Flatbuffer/Protobuf/gRPC?
@typeclass trait SmartContractsApi[F[_]] {
  def newEval(
      terms: Seq[Deploy],
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
  def noOpApi[F[_]: Applicative](
      storagePath: Path,
      storageSize: Long,
      storeType: StoreType
  ): SmartContractsApi[F] =
    new SmartContractsApi[F] {
      override def newEval(
          terms: scala.Seq[Deploy],
          initHash: ByteString,
          time: Option[Long] = None
      ): F[(ByteString, Seq[InternalProcessedDeploy])] =
        (ByteString.EMPTY, Seq.empty[InternalProcessedDeploy]).pure
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
    }
}
