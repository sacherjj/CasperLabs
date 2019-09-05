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
import io.casperlabs.casper.util.execengine.{
  DeployEffects,
  NoEffectsFailure,
  Op,
  ProcessedDeployResult
}
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
    // prestate hash, block time, protocol version, stream of deploys.
    type A = (ByteString, Long, ProtocolVersion, fs2.Stream[F, Deploy])
    type B = List[ProcessedDeployResult]
  }

  def apply[F[_]](implicit ev: DeploySelection[F]): DeploySelection[F] = ev

  private case class IntermediateState(
      accumulated: List[DeployEffects] = List.empty,
      diff: List[ProcessedDeployResult] = List.empty,
      accumulatedOps: OpMap[Key] = Map.empty
  ) {
    def effectsCommutativity: (List[DeployEffects], OpMap[state.Key]) =
      (accumulated, accumulatedOps)

    def size: Int = accumulated.map(_.deploy.serializedSize).sum

    // Appends new element to the intermediate state if it commutes with it.
    // Otherwise returns initial state.
    def addCommuting(deploysEffects: DeployEffects): IntermediateState = {
      val ops                  = Op.fromIpcEntry(deploysEffects.effects.opMap)
      val (accEffects, accOps) = effectsCommutativity
      if (accOps ~ ops)
        IntermediateState(deploysEffects :: accEffects, deploysEffects :: diff, accOps + ops)
      else
        this
    }
  }

  def create[F[_]: MonadThrowable: ExecutionEngineService: Fs2Compiler](
      sizeLimitBytes: Int
  ): DeploySelection[F] =
    new DeploySelection[F] {
      override def select(
          in: (DeployHash, Long, ProtocolVersion, fs2.Stream[F, Deploy])
      ): F[List[ProcessedDeployResult]] = {
        val (prestate, blocktime, protocolVersion, deploys) = in

        def go(
            state: IntermediateState,
            stream: fs2.Stream[F, Deploy]
        ): fs2.Pull[F, List[ProcessedDeployResult], Unit] =
          stream.pull.uncons.flatMap {
            case Some((chunk, streamTail)) =>
              // Fold over elements of the chunk, picking deploys that commute,
              // stop as soon as maximum block size limit is reached.
              val batch = chunk.toList
              val chunkResults =
                processDeploys[F](
                  prestate,
                  blocktime,
                  batch,
                  protocolVersion
                ) map { deployResults =>
                  val pdr = zipDeploysResults(batch, deployResults).toList
                  pdr.foldLeftM(state) {
                    case (accState, element: DeployEffects) =>
                      // newState is either `accState` if `element` doesn't commute,
                      // or contains `element` if it does.
                      val newState = accState.addCommuting(element)
                      // TODO: Use some base `Block` element to measure the size.
                      // If size of accumulated deploys is over 90% of the block limit, stop consuming more deploys.
                      if (newState.size > (0.9 * sizeLimitBytes)) {
                        // foldM will short-circuit for `Left`
                        // and continue for `Right`
                        accState.asLeft[IntermediateState]
                      } else newState.asRight[IntermediateState]
                    case (accState, element: NoEffectsFailure) =>
                      // InvalidDeploy-s should be pushed into the stream
                      // for later handling (like discarding invalid deploys).
                      accState.copy(diff = element :: accState.diff).asRight[IntermediateState]
                  }
                }

              fs2.Pull
                .eval(chunkResults)
                .flatMap {
                  // Block size limit reached. Output whatever was accumulated in the chunk and stop consuming.
                  case Left(deploys) =>
                    fs2.Pull.output(fs2.Chunk(deploys.diff.reverse)) >> fs2.Pull.done
                  case Right(deploys) =>
                    // Output what was chosen in the chunk and continue consuming the stream.
                    fs2.Pull.output(fs2.Chunk(deploys.diff.reverse)) >> go(
                      // We're emptying whatever was accumulated in the previous chunk,
                      // but we leave the total accumulated state, so we only pick deploys
                      // that commute with whatever was already chosen.
                      deploys.copy(diff = List.empty),
                      streamTail
                    )
                }
            case None => fs2.Pull.done
          }

        go(IntermediateState(), deploys).stream.compile.toList.map(_.flatten)
      }
    }
}
