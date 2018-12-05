package coop.rchain.casper.util.rholang

import com.google.protobuf.ByteString
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.models.Expr.ExprInstance.GString
import coop.rchain.models._
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.immutable
import scala.concurrent.SyncVar
import scala.util.{Failure, Success, Try}

//runtime is a SyncVar for thread-safety, as all checkpoints share the same "hot store"
class RuntimeManager private (val emptyStateHash: ByteString, runtimeContainer: SyncVar[Runtime]) {

  private def captureResults(start: StateHash, deploy: Deploy, name: String = "__SCALA__")(
      implicit scheduler: Scheduler
  ): Seq[Par] = captureResults(start, deploy, Par().withExprs(Seq(Expr(GString(name)))))

  private def captureResults(start: StateHash, deploy: Deploy, name: Par)(
      implicit scheduler: Scheduler
  ): Seq[Par] = ???

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

  def computeBonds(hash: StateHash)(implicit scheduler: Scheduler): Seq[Bond] = {
    val bondsQuery =
      """new rl(`rho:registry:lookup`), SystemInstancesCh, posCh in {
        |  rl!(`rho:id:wdwc36f4ixa6xacck3ddepmgueum7zueuczgthcqp6771kdu8jogm8`, *SystemInstancesCh) |
        |  for(@(_, SystemInstancesRegistry) <- SystemInstancesCh) {
        |    @SystemInstancesRegistry!("lookup", "pos", *posCh) |
        |    for(pos <- posCh){ pos!("getBonds", "__SCALA__") }
        |  }
        |}""".stripMargin

    val bondsQueryTerm =
      ProtoUtil.deployDataToDeploy(ProtoUtil.sourceDeploy(bondsQuery, 0L, ???))
    val bondsPar = captureResults(hash, bondsQueryTerm)

    assert(
      bondsPar.size == 1,
      s"Incorrect number of results from query of current bonds: ${bondsPar.size}"
    )
    toBondSeq(bondsPar.head)
  }

  private def toBondSeq(bondsMap: Par): Seq[Bond] =
    bondsMap.exprs.head.getEMapBody.ps.map {
      case (validator: Par, bond: Par) =>
        assert(validator.exprs.length == 1, "Validator in bonds map wasn't a single string.")
        assert(bond.exprs.length == 1, "Stake in bonds map wasn't a single integer.")
        val validatorName = validator.exprs.head.getGByteArray
        val stakeAmount   = bond.exprs.head.getETupleBody.ps.head.exprs.head.getGInt
        Bond(validatorName, stakeAmount)
    }.toList

  def getData(hash: ByteString, channel: Par)(
      implicit scheduler: Scheduler
  ): Seq[Par] =
    ???

  def getContinuation(
      hash: ByteString,
      channels: immutable.Seq[Par]
  )(
      implicit scheduler: Scheduler
  ): Seq[(Seq[BindPattern], Par)] = ???

}

object RuntimeManager {
  type StateHash = ByteString
}
