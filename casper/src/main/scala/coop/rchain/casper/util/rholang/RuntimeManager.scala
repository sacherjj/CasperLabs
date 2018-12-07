package coop.rchain.casper.util.rholang

import com.google.protobuf.ByteString
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.immutable
import scala.concurrent.SyncVar

//runtime is a SyncVar for thread-safety, as all checkpoints share the same "hot store"
class RuntimeManager private (val emptyStateHash: ByteString, runtimeContainer: SyncVar[Runtime]) {
  def replayComputeState(
      hash: StateHash,
      terms: Seq[InternalProcessedDeploy],
      time: Option[Long] = None
  ): Task[Either[(Option[Deploy], Failed), StateHash]] = ???

  def computeState(
      hash: StateHash,
      terms: Seq[Deploy],
      time: Option[Long] = None
  ): Task[(StateHash, Seq[InternalProcessedDeploy])] = ???

  def computeBonds(hash: StateHash)(implicit scheduler: Scheduler): Seq[Bond] = ???
}

object RuntimeManager {
  def fromRuntime(activeRuntime: Runtime): RuntimeManager = ???

  type StateHash = ByteString
}
