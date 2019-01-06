package io.casperlabs.casper.util.rholang

import cats.effect.Concurrent
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.ipc.{CommutativeEffects, ExecutionEffect}
import io.casperlabs.models._
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
  ): Task[Either[(Option[Deploy], Failed), StateHash]] =
    smartContractsApi.replayEval(terms, hash, time)

  def computeState(
      hash: StateHash,
      terms: Seq[(Deploy, ExecutionEffect)],
      time: Option[Long] = None
  ): Task[(StateHash, Seq[InternalProcessedDeploy])] =
    smartContractsApi.newEval(terms, hash, time)

  // todo this should be complemented
  def computeBonds(hash: StateHash)(implicit scheduler: Scheduler): Seq[Bond] = Seq()

  def sendDeploy(d: DeployData): F[Either[Throwable, ExecutionEffect]] =
    ToAbstractContext[F].fromTask(smartContractsApi.sendDeploy(d))
}

object RuntimeManager {
  type StateHash = ByteString

  def fromSmartContractApi(smartContractsApi: SmartContractsApi[Task]): RuntimeManager[Task] =
    //TODO define 'emptyStateHash'
    new RuntimeManager(smartContractsApi, ByteString.EMPTY)
}
