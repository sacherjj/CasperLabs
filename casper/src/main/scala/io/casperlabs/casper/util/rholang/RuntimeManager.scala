package io.casperlabs.casper.util.rholang

import cats.effect.Concurrent
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.functor._
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.ipc.{CommutativeEffects, ExecutionEffect, Deploy => IPCDeploy}
import io.casperlabs.models._
import io.casperlabs.shared.{Log, LogSource}
import io.casperlabs.smartcontracts.ExecutionEngineService

//TODO: Delete this class
class RuntimeManager[F[_]: Concurrent] private (
    val executionEngineService: ExecutionEngineService[F],
    val emptyStateHash: StateHash,
    // Pass in the genesis bonds until we have a solution based on the BlockStore.
    initialBonds: Seq[Bond]
) {
  // TODO: This function should return more information than just StateHash.
  // We need also check whether the cost of execution is the same as one published
  // by original validator and whether result of running each deploy is the same (failure vs success)
  def replayComputeState(
      hash: StateHash,
      terms: Seq[InternalProcessedDeploy],
      time: Option[Long] = None
  ): F[Either[(Option[Deploy], Failed), StateHash]] =
    hash.asRight[(Option[Deploy], Failed)].pure

  def computeState(
      hash: StateHash,
      terms: Seq[(Deploy, ExecutionEffect)],
      time: Option[Long] = None
  )(implicit log: Log[F]): F[(StateHash, Seq[InternalProcessedDeploy])] =
    //replaced by ExecEngineUtil
    (RuntimeManager.emptyStateHash, Seq.empty[InternalProcessedDeploy]).pure

  // todo this should be complemented
  def computeBonds(hash: StateHash)(implicit log: Log[F]): F[Seq[Bond]] =
    // FIXME: Implement bonds!
    initialBonds.pure[F]

  //replaced by ExecEngineUtil
  def sendDeploy(d: IPCDeploy): F[Either[Throwable, ExecutionEffect]] =
    Concurrent[F]
      .pure[Either[Throwable, ExecutionEffect]](Left(new Exception("CLASS TO BE DELETED")))
}

object RuntimeManager {
  type StateHash = ByteString

  private[rholang] val emptyStateHash = ByteString.copyFrom(Array.fill(32)(0.toByte))

  def fromExecutionEngineService[F[_]: Concurrent](
      executionEngineService: ExecutionEngineService[F]
  ): RuntimeManager[F] =
    new RuntimeManager(executionEngineService, emptyStateHash, Seq.empty)

  def apply[F[_]: Concurrent](
      executionEngineService: ExecutionEngineService[F],
      bonds: Map[Array[Byte], Long]
  ): RuntimeManager[F] =
    new RuntimeManager(executionEngineService, emptyStateHash, bonds.map {
      case (validator, weight) => Bond(ByteString.copyFrom(validator), weight)
    }.toSeq)
}
