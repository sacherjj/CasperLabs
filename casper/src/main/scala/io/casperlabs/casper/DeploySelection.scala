package io.casperlabs.casper

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion}
import io.casperlabs.casper.consensus.{state, Deploy}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{processDeploys, zipDeploysResults}
import io.casperlabs.casper.util.execengine.Op.OpMap
import io.casperlabs.casper.util.execengine.{
  DeployEffects,
  Op,
  PreconditionFailure,
  ProcessedDeployResult
}
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.metrics.Metrics
import io.casperlabs.smartcontracts.ExecutionEngineService

trait Select[F[_]] {
  type A
  type B
  def select(in: A): F[B]
}

object DeploySelection {

  final case class DeploySelectionResult(
      commuting: List[DeployEffects],
      conflicting: List[Deploy],
      preconditionFailures: List[PreconditionFailure]
  )

  trait DeploySelection[F[_]] extends Select[F] {
    // prestate hash, block time, protocol version, stream of deploys.
    type A = (ByteString, Long, ProtocolVersion, fs2.Stream[F, Deploy])
    type B = DeploySelectionResult
  }

  def apply[F[_]](implicit ev: DeploySelection[F]): DeploySelection[F] = ev

  private case class IntermediateState(
      // Chosen deploys that commute.
      accumulated: List[DeployEffects] = List.empty,
      diff: List[DeployEffects] = List.empty,
      // For quicker commutativity test.
      // New deploy has to commute with all the effects accumulated so far.
      accumulatedOps: OpMap[Key] = Map.empty,
      // Deploys that conflict with `accumulated` but will be included
      // as SEQ deploys.
      conflicting: List[Deploy] = List.empty,
      preconditionFailures: List[PreconditionFailure] = List.empty
  ) {
    def effectsCommutativity: (List[DeployEffects], OpMap[state.Key]) =
      (accumulated, accumulatedOps)

    // We have to take into account conflicting deploys as well since they will
    // also be included in a block in SEQ sections.
    def size: Int =
      accumulated.map(_.deploy.serializedSize).sum + conflicting.map(_.serializedSize).sum

    // Appends new element to the intermediate state if it commutes with it.
    // Otherwise returns initial state.
    def addCommuting(deploysEffects: DeployEffects): IntermediateState = {
      val ops                  = Op.fromIpcEntry(deploysEffects.effects.opMap)
      val (accEffects, accOps) = effectsCommutativity
      if (accOps ~ ops)
        IntermediateState(deploysEffects :: accEffects, deploysEffects :: diff, accOps + ops)
      else
        // We're not updating the `diff` here since its elements are pushed
        // to the stream and we do that for commuting elements.
        copy(conflicting = deploysEffects.deploy :: this.conflicting)
    }

    def addPreconditionFailure(failure: PreconditionFailure): IntermediateState =
      copy(preconditionFailures = failure :: this.preconditionFailures)
  }

  def createMetered[F[_]: Sync: ExecutionEngineService: Fs2Compiler: Metrics](
      sizeLimitBytes: Int
  ): DeploySelection[F] = {
    import io.casperlabs.smartcontracts.GrpcExecutionEngineService.EngineMetricsSource
    val underlying = create[F](sizeLimitBytes)
    new DeploySelection[F] {
      override def select(
          in: (DeployHash, Long, ProtocolVersion, fs2.Stream[F, Deploy])
      ): F[DeploySelectionResult] =
        Metrics[F].timer("deploySelection")(underlying.select(in))
    }

  }

  def create[F[_]: Sync: ExecutionEngineService: Fs2Compiler](
      sizeLimitBytes: Int
  ): DeploySelection[F] =
    new DeploySelection[F] {
      override def select(
          in: (DeployHash, Long, ProtocolVersion, fs2.Stream[F, Deploy])
      ): F[DeploySelectionResult] = {
        val (prestate, blocktime, protocolVersion, deploys) = in

        def go(
            state: IntermediateState,
            stream: fs2.Stream[F, Deploy],
            conflictingRef: Ref[F, List[Deploy]],
            preconditionFailuresRef: Ref[F, List[PreconditionFailure]]
        ): fs2.Pull[F, List[DeployEffects], Unit] =
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
                    case (accState, element: PreconditionFailure) =>
                      // PreconditionFailure-s should be pushed into the stream
                      // for later handling (like discarding invalid deploys).
                      accState.addPreconditionFailure(element).asRight[IntermediateState]
                  }
                }

              fs2.Pull
                .eval(chunkResults)
                .flatMap {
                  // Block size limit reached. Output whatever was accumulated in the chunk and stop consuming.
                  case Left(intermediateState) =>
                    fs2.Pull.output(fs2.Chunk(intermediateState.diff.reverse)).covary[F] >>
                      fs2.Pull.eval(conflictingRef.set(intermediateState.conflicting)).void >>
                      fs2.Pull
                        .eval(preconditionFailuresRef.set(intermediateState.preconditionFailures))
                        .void >>
                      fs2.Pull.done
                  case Right(deploys) =>
                    // Output what was chosen in the chunk and continue consuming the stream.
                    fs2.Pull.output(fs2.Chunk(deploys.diff.reverse)).covary[F] >> go(
                      // We're emptying whatever was accumulated in the previous chunk,
                      // but we leave the total accumulated state, so we only pick deploys
                      // that commute with whatever was already chosen.
                      deploys.copy(diff = List.empty),
                      streamTail,
                      conflictingRef,
                      preconditionFailuresRef
                    )
                }
            case None =>
              fs2.Pull.eval(conflictingRef.set(state.conflicting)).void >>
                fs2.Pull.eval(preconditionFailuresRef.set(state.preconditionFailures)).void >>
                fs2.Pull.done
          }

        for {
          conflictingRef          <- Ref[F].of(List.empty[Deploy])
          preconditionFailuresRef <- Ref[F].of(List.empty[PreconditionFailure])
          commutingDeploys <- go(
                               IntermediateState(),
                               deploys,
                               conflictingRef,
                               preconditionFailuresRef
                             ).stream.compile.toList
                               .map(_.flatten)
          conflictingDeploys   <- conflictingRef.get
          preconditionFailures <- preconditionFailuresRef.get
        } yield DeploySelectionResult(commutingDeploys, conflictingDeploys, preconditionFailures)
      }
    }
}
