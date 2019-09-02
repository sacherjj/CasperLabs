package io.casperlabs.casper

import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{state, Deploy}
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion}
import io.casperlabs.casper.deploybuffer.DeployBuffer
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{
  handleInvalidDeploys,
  processDeploys,
  zipDeploysResults
}
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
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
    type A = (ByteString, Long, ProtocolVersion, fs2.Stream[F, List[Deploy]])
    type B = List[DeployEffects]
  }

  def apply[F[_]](implicit ev: DeploySelection[F]): DeploySelection[F] = ev

  private case class IntermediateState(
      accumulated: List[DeployEffects] = List.empty,
      diff: List[DeployEffects] = List.empty,
      accumulatedOps: OpMap[Key] = Map.empty
  ) {
    def chosen: List[DeployEffects] = accumulated ++ diff
    def effectsCommutativity: (List[DeployEffects], OpMap[state.Key]) =
      (chosen, accumulatedOps)
    def size: Int = chosen.map(_.deploy.serializedSize).sum
  }

  // Appends new element to the intermediate state if it commutes with it.
  // Otherwise returns initial state.
  private def commutes(init: IntermediateState, el: DeployEffects): IntermediateState = {
    val ops                  = Op.fromIpcEntry(el.effects.opMap)
    val (accEffects, accOps) = init.effectsCommutativity
    if (accOps ~ ops)
      IntermediateState(el :: accEffects, el :: init.diff, accOps + ops)
    else
      init
  }

  def unsafeCreate[F[_]: MonadThrowable: ExecutionEngineService: DeployBuffer: Log: Fs2Compiler](
      sizeLimitMB: Int
  ): DeploySelection[F] =
    new DeploySelection[F] {
      override def select(
          in: (DeployHash, Long, ProtocolVersion, fs2.Stream[F, List[Deploy]])
      ): F[List[DeployEffects]] = {
        val (prestate, blocktime, protocolVersion, hashes) = in

        def go(
            state: IntermediateState,
            stream: fs2.Stream[F, List[Deploy]]
        ): fs2.Pull[F, List[DeployEffects], Unit] =
          stream.pull.uncons.flatMap {
            case Some((chunk, streamTail)) =>
              // Fold over elements of the chunk, picking deploys that commute,
              // stop as soon as maximum block size limit is reached.
              val chunkResults = chunk.toList
                .foldM[F, Either[IntermediateState, IntermediateState]](
                  state.asRight[IntermediateState]
                ) {
                  case (stateE, batch) =>
                    val state = stateE.fold(identity, identity)
                    for {
                      dr <- processDeploys[F](
                             prestate,
                             blocktime,
                             batch,
                             protocolVersion
                           )
                      pdr                                = zipDeploysResults(batch, dr).toList
                      (invalidDeploys, effectfulDeploys) = ProcessedDeployResult.split(pdr)
                      _                                  <- handleInvalidDeploys[F](invalidDeploys)
                    } yield {
                      effectfulDeploys.foldLeftM(state) {
                        case (accState, element) =>
                          // newState is either `accState` if `element` doesn't commute,
                          // or contains `element` if it does.
                          val newState = commutes(accState, element)
                          // TODO: Use some base `Block` element to measure the size.
                          // If size if accumulated deploys is over 90% of the block limit, stop consuming more deploys.
                          if (newState.size > (0.9 * sizeLimitMB)) {
                            // foldM will short-circuit for `Left`
                            // and continue for `Right`
                            accState.asLeft[IntermediateState]
                          } else newState.asRight[IntermediateState]
                      }
                    }
                }

              fs2.Pull
                .eval(chunkResults)
                .flatMap {
                  // Block size limit reached. Output whatever was accumulated in the chunk and stop consuming.
                  case Left(deploys)  => fs2.Pull.output(fs2.Chunk(deploys.diff)) >> fs2.Pull.done
                  case Right(deploys) =>
                    // Output what was chosen in the chunk and continue consuming the stream.
                    fs2.Pull.output(fs2.Chunk(deploys.diff)) >> go(
                      // We're emptying whatever was accumulated in the previous chunk,
                      // but we leave the total accumulated state, so we only pick deploys
                      // that commute with whatever was already chosen.
                      deploys.copy(diff = List.empty),
                      streamTail
                    )
                }
            case None => fs2.Pull.done
          }

        go(IntermediateState(), hashes).stream.compile.toList.map(_.flatten)
      }
    }

  def create[F[_]: Sync: ExecutionEngineService: DeployBuffer: Log](
      sizeLimitMB: Int
  ): F[DeploySelection[F]] =
    Sync[F].delay {
      unsafeCreate[F](sizeLimitMB)
    }
}
