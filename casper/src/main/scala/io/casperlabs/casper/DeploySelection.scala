package io.casperlabs.casper

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion}
import io.casperlabs.casper.consensus.{state, Deploy}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.eeExecuteDeploys
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
import shapeless.tag.@@

trait Select[F[_]] {
  type A
  type B
  def select(in: A): F[B]
}

object DeploySelection {

  sealed trait CommutingDeploysTag

  type CommutingDeploys = NonEmptyList[Deploy] @@ CommutingDeploysTag
  object CommutingDeploys {
    def apply(deploys: NonEmptyList[Deploy]): CommutingDeploys =
      deploys.asInstanceOf[CommutingDeploys]

    def apply(deploy: Deploy): CommutingDeploys =
      NonEmptyList.one(deploy).asInstanceOf[CommutingDeploys]

    class CommutingDeploysOps(deploys: CommutingDeploys) {
      def getDeploys: NonEmptyList[Deploy] = deploys
    }

    implicit def commutingDeploysOps(commutingDeploys: CommutingDeploys): CommutingDeploysOps =
      new CommutingDeploysOps(commutingDeploys)
  }

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
      commuting: List[DeployEffects] = List.empty,
      // For quicker commutativity test.
      // New deploy has to commute with all the effects accumulated so far.
      accumulatedOps: OpMap[Key] = Map.empty,
      // Deploys that conflict with `accumulated` but will be included
      // as SEQ deploys.
      conflicting: List[Deploy] = List.empty,
      preconditionFailures: List[PreconditionFailure] = List.empty
  ) {
    def effectsCommutativity: (List[DeployEffects], OpMap[state.Key]) =
      (commuting, accumulatedOps)

    // We have to take into account conflicting deploys as well since they will
    // also be included in a block in SEQ sections.
    def size: Int =
      commuting.map(_.deploy.serializedSize).sum + conflicting.map(_.serializedSize).sum

    // Appends new element to the intermediate state if it commutes with it.
    // Otherwise returns initial state.
    def addCommuting(deploysEffects: DeployEffects): IntermediateState = {
      val ops                  = Op.fromIpcEntry(deploysEffects.effects.opMap)
      val (accEffects, accOps) = effectsCommutativity
      if (accOps ~ ops) {
        copy(
          commuting = deploysEffects :: accEffects,
          accumulatedOps = accOps + ops
        )
      } else
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

  private final case class ShortcutDeployConsumption(is: IntermediateState) extends Throwable

  /** Creates an instance of deploy selection algorithm.
    *
    * @param sizeLimitBytes Maximum block size. An implementation should respect a maximum
    *                       size of a block and never return more deploys that can fit into a block.
    * @param minChunkSize A minimum size of chunk of the deploy stream. This concrete implementation will
    *                     try to work with chunks of size `minChunkSize` but will allow for smaller chunks
    *                     if there's not enough elements in the stream.
    * @tparam F
    * @return An instance of `DeploySelection` trait.
    */
  def create[F[_]: Sync: ExecutionEngineService: Fs2Compiler](
      sizeLimitBytes: Int,
      minChunkSize: Int = 10
  ): DeploySelection[F] =
    new DeploySelection[F] {
      override def select(
          in: (DeployHash, Long, ProtocolVersion, fs2.Stream[F, Deploy])
      ): F[DeploySelectionResult] = {
        val (prestate, blocktime, protocolVersion, deploys) = in

        val selectedDeploys = deploys
          .chunkMin(minChunkSize)
          .evalScan(IntermediateState()) {
            case (state, batch) =>
              eeExecuteDeploys[F](
                prestate,
                blocktime,
                batch.toList,
                protocolVersion
              )(ExecutionEngineService[F].exec _) flatMap {
                _.foldLeftM(state) {
                  case (accState, element: DeployEffects) =>
                    // newState is either `accState` if `element` doesn't commute,
                    // or contains `element` if it does.
                    val newState = accState.addCommuting(element)
                    // TODO: Use some base `Block` element to measure the size.
                    // If size of accumulated deploys is over 90% of the block limit, stop consuming more deploys.
                    if (newState.size > (0.9 * sizeLimitBytes)) {
                      // foldM will short-circuit for `Left`
                      // and continue for `Right`
                      ShortcutDeployConsumption(accState).raiseError[F, IntermediateState]
                    } else newState.pure[F]
                  case (accState, element: PreconditionFailure) =>
                    // PreconditionFailure-s should be pushed into the stream
                    // for later handling (like discarding invalid deploys).
                    accState.addPreconditionFailure(element).pure[F]
                }
              }
          }
          .compile
          .lastOrError
          .recover {
            case ShortcutDeployConsumption(is) => is
          }

        selectedDeploys.map(
          result =>
            DeploySelectionResult(
              result.commuting.reverse,
              result.conflicting.reverse,
              result.preconditionFailures.reverse
            )
        )
      }
    }
}
