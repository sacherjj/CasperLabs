package io.casperlabs.casper

import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion}
import io.casperlabs.casper.deploybuffer.DeployBuffer
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{
  handleInvalidDeploys,
  processDeploys,
  zipDeploysResults
}
import io.casperlabs.casper.util.execengine.{DeployEffects, Op, ProcessedDeployResult}
import io.casperlabs.casper.util.execengine.Op.OpMap
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

trait Select[F[_]] {
  type A
  type B
  def select(in: A): F[B]
}

object DeploySelection {

  trait DeploySelection[F[_]] extends Select[F] {
    type A = (ByteString, Long, ProtocolVersion, Set[DeployHash])
    type B = List[DeployEffects]
  }

  def apply[F[_]](implicit ev: DeploySelection[F]): DeploySelection[F] = ev

  private case class IntermediateState(
      chosen: List[DeployEffects] = List.empty,
      accumulatedOps: OpMap[Key] = Map.empty
  ) {
    def effectsCommutativity: (List[DeployEffects], OpMap[state.Key]) =
      (chosen, accumulatedOps)
    def size: Long = chosen.map(_.deploy.serializedSize).sum.toLong
  }

  // Appends new element to the intermediate state if it commutes with it.
  // Otherwise returns initial state.
  private def commutes(init: IntermediateState, el: DeployEffects): IntermediateState = {
    val ops                  = Op.fromIpcEntry(el.effects.opMap)
    val (accEffects, accOps) = init.effectsCommutativity
    if (accOps ~ ops)
      init.copy(el :: accEffects, accOps + ops)
    else
      init
  }

  def create[F[_]: Sync: ExecutionEngineService: DeployBuffer: Log](
      sizeLimitMB: Long
  ): F[DeploySelection[F]] =
    Sync[F].delay {
      new DeploySelection[F] {
        override def select(
            in: (DeployHash, Long, ProtocolVersion, Set[DeployHash])
        ): F[List[DeployEffects]] = {
          val (prestate, blocktime, protocolVersion, hashes) = in

          hashes
            .grouped(50)
            .toList
            .foldM[F, Either[IntermediateState, IntermediateState]](
              IntermediateState().asRight[IntermediateState]
            ) {
              case (stateE, batch) =>
                val state = stateE.fold(identity, identity)
                for {
                  deploys <- DeployBuffer[F].getByHashes(batch.toList)
                  dr <- processDeploys[F](
                         prestate,
                         blocktime,
                         deploys,
                         protocolVersion
                       )
                  pdr                                = zipDeploysResults(deploys, dr).toList
                  (invalidDeploys, effectfulDeploys) = ProcessedDeployResult.split(pdr)
                  _                                  <- handleInvalidDeploys[F](invalidDeploys)
                } yield {
                  effectfulDeploys.foldLeftM(state) {
                    case (chosenDeploys, element) =>
                      if ((chosenDeploys.size + element.deploy.serializedSize) > (0.9 * sizeLimitMB)) {
                        chosenDeploys.asLeft[IntermediateState]
                      } else {
                        commutes(chosenDeploys, element).asRight[IntermediateState]
                      }
                  }
                }
            }
            .map(_.fold(_.chosen, _.chosen))
        }
      }
    }
}
