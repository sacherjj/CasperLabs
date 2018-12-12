package coop.rchain.casper.util.rholang

import com.google.protobuf.ByteString
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.models._
import coop.rchain.smartcontracts.SmartContractsApi
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

  def computeBonds(hash: StateHash)(implicit scheduler: Scheduler): Seq[Bond] = ???
}

object RuntimeManager {
  type StateHash = ByteString

  def fromSmartContractApi(smartContractsApi: SmartContractsApi[Task]): RuntimeManager =
    //TODO define 'emptyStateHash'
    new RuntimeManager(smartContractsApi, ByteString.EMPTY)
}
