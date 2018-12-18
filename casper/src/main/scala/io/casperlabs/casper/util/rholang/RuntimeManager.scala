package io.casperlabs.casper.util.rholang

import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.models._
import io.casperlabs.smartcontracts.SmartContractsApi
import monix.eval.Task
import monix.execution.Scheduler

class RuntimeManager private (
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
      terms: Seq[Deploy],
      time: Option[Long] = None
  ): Task[(StateHash, Seq[InternalProcessedDeploy])] =
    smartContractsApi.newEval(terms, hash, time)

  // todo this should be complemented
  def computeBonds(hash: StateHash)(implicit scheduler: Scheduler): Seq[Bond] = Seq()
}

object RuntimeManager {
  type StateHash = ByteString

  def fromSmartContractApi(smartContractsApi: SmartContractsApi[Task]): RuntimeManager =
    //TODO define 'emptyStateHash'
    new RuntimeManager(smartContractsApi, ByteString.EMPTY)
}
