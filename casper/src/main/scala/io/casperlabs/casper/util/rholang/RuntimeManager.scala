package io.casperlabs.casper.util.rholang

import cats.effect.Concurrent
import cats.syntax.applicative._
import cats.syntax.functor._
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.ipc.{Deploy => IPCDeploy}
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.ipc.{CommutativeEffects, ExecutionEffect}
import io.casperlabs.models._
import cats.syntax.either._
import io.casperlabs.smartcontracts.{ExecutionEngineService, SmartContractsApi}
import monix.eval.Task
import monix.execution.Scheduler

class RuntimeManager[F[_]: Concurrent: ToAbstractContext] private (
    val smartContractsApi: SmartContractsApi[Task],
    val emptyStateHash: StateHash
) {
  def replayComputeState(
      hash: StateHash,
      terms: Seq[InternalProcessedDeploy],
      time: Option[Long] = None
  ): F[Either[(Option[Deploy], Failed), StateHash]] =
    ByteString.EMPTY.asRight[(Option[Deploy], Failed)].pure

  def computeState(
      hash: StateHash,
      terms: Seq[(Deploy, ExecutionEffect)],
      time: Option[Long] = None
  ): F[(StateHash, Seq[InternalProcessedDeploy])] = {
    // todo using maximum commutative rules
    val commutativeEffects = CommutativeEffects(terms.flatMap(_._2.transformMap))
    ToAbstractContext[F]
      .fromTask { smartContractsApi.executeEffects(commutativeEffects) }
      .map {
        case Right(_) =>
          (hash, terms.map(it => InternalProcessedDeploy(it._1, 0, Succeeded)))
        case Left(err) =>
          throw new IllegalArgumentException(
            s"Failed to execute effects: $err"
          )
      }
  }

  // todo this should be complemented
  def computeBonds(hash: StateHash)(implicit scheduler: Scheduler): Seq[Bond] = Seq()

  def sendDeploy(d: IPCDeploy): F[Either[Throwable, ExecutionEffect]] =
    ToAbstractContext[F].fromTask(smartContractsApi.sendDeploy(d))
}

object RuntimeManager {
  type StateHash = ByteString

  def fromSmartContractApi(smartContractsApi: SmartContractsApi[Task]): RuntimeManager[Task] =
    //TODO define 'emptyStateHash'
    new RuntimeManager(smartContractsApi, ByteString.EMPTY)
}
