package io.casperlabs.casper.util.execengine

import cats.effect._
import cats.implicits._
import cats.kernel.Monoid
import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.{Foldable, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.{
  CommutingDeploys,
  DeploySelection,
  DeploySelectionResult
}
import CommutingDeploys._
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.state.{CLType, CLValue, Key, ProtocolVersion, StoredValue}
import io.casperlabs.casper.consensus.{state, Block, Deploy}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.execengine.Op.{OpMap, OpMapAddComm}
import io.casperlabs.casper.util.{CasperLabsProtocol, DagOperations, ProtoUtil}
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.ipc._
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.{Message, SmartContractEngineError, DeployResult => _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageWriter}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.metrics.implicits._
import io.casperlabs.smartcontracts.ExecutionEngineService.CommitResult

import scala.util.Either

case class DeploysCheckpoint(
    preStateHash: StateHash,
    postStateHash: StateHash,
    bondedValidators: Seq[io.casperlabs.casper.consensus.Bond],
    deploysForBlock: Seq[Block.ProcessedDeploy],
    protocolVersion: state.ProtocolVersion
)

object ExecEngineUtil {
  type StateHash = ByteString

  case class InvalidDeploys(
      preconditionFailures: List[PreconditionFailure]
  )

  import io.casperlabs.smartcontracts.GrpcExecutionEngineService.EngineMetricsSource

  // A type signature of the `ExecutionEngineService.exec` endpoint.
  // Used in places where we only need `exec` method and we want to do "something" with it.
  // Like counting number of `exec` calls or tracking what effects were sent to EE (in case of `commit`).
  type EEExecFun[F[_]] = (
      ByteString, // Prestate hash
      Long,       // Block time
      Seq[DeployItem],
      state.ProtocolVersion
  ) => F[Either[Throwable, Seq[DeployResult]]]

  // A type signature of the `ExecutionEngineService.commit` endpoint.
  type EECommitFun[F[_]] = (
      ByteString,          // Prestate hash
      Seq[TransformEntry], // Effects to commit
      ProtocolVersion
  ) => F[Either[Throwable, ExecutionEngineService.CommitResult]]

  /** Commit effects against ExecutionEngine as described by `blockEffects`.
    *
    * Sends effects in an ordered sequence as defined by `stage` index of each batch in [[BlockEffects]].
    *
    * @param initPreStateHash initial pre-state hash
    * @param protocolVersion protocol version for ALL deploys' effects
    * @param blockEffects block's effects to commit.
    * @return Result of committing deploys. Returns the first error it encounters or last [[io.casperlabs.smartcontracts.ExecutionEngineService.CommitResult]].
    */
  def commitEffects[F[_]: MonadThrowable](
      initPreStateHash: ByteString,
      protocolVersion: ProtocolVersion,
      blockEffects: BlockEffects
  )(implicit E: ExecutionEngineService[F]): F[ExecutionEngineService.CommitResult] =
    blockEffects.effects.toList.sortBy(_._1).foldM(CommitResult(initPreStateHash, Seq.empty)) {
      case (state, (_, transforms)) =>
        E.commit(state.postStateHash, transforms.toList, protocolVersion).rethrow
    }

  /**
    * Sends a group of commuting deploys to EE.exec endpoint and then commits their effects.
    * Any error is raised to `MonadThrowable` context.
    *
    * Returns either a list of precondition failures if any of the deploys fail execution,
    * or result of committing the effects.
    */
  protected[execengine] def execCommitParDeploys[F[_]: MonadThrowable](
      stage: Int,
      prestate: ByteString,
      blocktime: Long,
      protocolVersion: state.ProtocolVersion,
      deploys: CommutingDeploys
  )(
      eeExec: EEExecFun[F],
      eeCommit: EECommitFun[F]
  ): F[Either[List[PreconditionFailure], DeploysCheckpoint]] =
    eeExecuteDeploys[F](prestate, blocktime, deploys.getDeploys.toList, protocolVersion)(eeExec)
      .flatMap { results =>
        val (failures, deployEffects) = ProcessedDeployResult.split(results)
        if (failures.nonEmpty)
          failures.asLeft[DeploysCheckpoint].pure[F]
        else {
          val (deploysForBlock, transforms) = ExecEngineUtil
            .unzipEffectsAndDeploys(deployEffects, stage)
            .unzip
          eeCommit(prestate, transforms.flatten, protocolVersion).rethrow
            .map { commitResult =>
              DeploysCheckpoint(
                prestate,
                commitResult.postStateHash,
                commitResult.bondedValidators,
                deploysForBlock,
                protocolVersion
              ).asRight[List[PreconditionFailure]]
            }
        }
      }

  /** Takes a list of deploys and executes them one after the other, using a post state hash of a previous
    * deploy as pre state hash of the current deploy.
    *
    * In essence, this simulates sequential execution EE should be providing natively.
    */
  protected[execengine] def execCommitSeqDeploys[F[_]: MonadThrowable: Log: Metrics: DeployStorage: ExecutionEngineService](
      prestateHash: ByteString,
      blocktime: Long,
      protocolVersion: state.ProtocolVersion,
      deploys: NonEmptyList[Deploy]
  )(eeExec: EEExecFun[F], eeCommit: EECommitFun[F]): F[DeploysCheckpoint] = {

    final case class State(
        preconditionFailures: List[PreconditionFailure],
        postStateHash: ByteString,
        blockEffects: Seq[Block.ProcessedDeploy],
        bondedValidators: Seq[io.casperlabs.casper.consensus.Bond]
    )

    object State {
      def init: State = State(List.empty, prestateHash, Seq.empty, Seq.empty)
    }

    // We have to move idx one to the right (start with `1`) as `0` is reserved for commuting deploys.
    val deploysWithStage = deploys.sortBy(_.getHeader.timestamp).zipWithIndex.map {
      case (deploy, idx) => deploy -> (idx + 1)
    }

    for {
      state <- deploysWithStage.foldLeftM(State.init) {
                case (state, (deploy, stage)) =>
                  execCommitParDeploys[F](
                    stage,
                    state.postStateHash,
                    blocktime,
                    protocolVersion,
                    CommutingDeploys(deploy)
                  )(
                    eeExec,
                    eeCommit
                  ).map {
                    case Left(preconditionFailures) =>
                      state.copy(
                        preconditionFailures = preconditionFailures ::: state.preconditionFailures
                      )
                    case Right(deploysCheckpoint) =>
                      state.copy(
                        postStateHash = deploysCheckpoint.postStateHash,
                        blockEffects = state.blockEffects ++ deploysCheckpoint.deploysForBlock,
                        bondedValidators = deploysCheckpoint.bondedValidators
                      )
                  }
              }
      _ <- handleInvalidDeploys[F](state.preconditionFailures)
            .whenA(state.preconditionFailures.nonEmpty)
    } yield DeploysCheckpoint(
      prestateHash,
      state.postStateHash,
      state.bondedValidators,
      state.blockEffects,
      protocolVersion
    )
  }

  /** Given a set of chosen parents create a "deploy checkpoint".
    */
  def computeDeploysCheckpoint[F[_]: MonadThrowable: DeployStorage: Log: ExecutionEngineService: DeploySelection: Metrics](
      merged: MergeResult[TransformMap, Block],
      deployStream: fs2.Stream[F, Deploy],
      blocktime: Long,
      protocolVersion: state.ProtocolVersion,
      rank: Long,
      upgrades: Seq[ChainSpec.UpgradePoint]
  ): F[DeploysCheckpoint] = Metrics[F].timer("computeDeploysCheckpoint") {
    for {
      preStateHash <- computePrestate[F](merged, rank, upgrades).timer("computePrestate")
      DeploySelectionResult(commuting, conflicting, preconditionFailures) <- DeploySelection[F]
                                                                              .select(
                                                                                (
                                                                                  preStateHash,
                                                                                  blocktime,
                                                                                  protocolVersion,
                                                                                  deployStream
                                                                                )
                                                                              )
      _                             <- handleInvalidDeploys[F](preconditionFailures)
      (deploysForBlock, transforms) = ExecEngineUtil.unzipEffectsAndDeploys(commuting).unzip
      parResult <- ExecutionEngineService[F]
                    .commit(preStateHash, transforms.flatten, protocolVersion)
                    .rethrow
      result <- NonEmptyList
                 .fromList(conflicting)
                 .fold(
                   // All deploys in the block commute.
                   DeploysCheckpoint(
                     preStateHash,
                     parResult.postStateHash,
                     parResult.bondedValidators,
                     deploysForBlock,
                     protocolVersion
                   ).pure[F]
                 )( // Execute conflicting deploys in a sequence.
                   nelDeploys =>
                     execCommitSeqDeploys[F](
                       parResult.postStateHash,
                       blocktime,
                       protocolVersion,
                       nelDeploys
                     )(
                       (ExecutionEngineService[F].exec _),
                       (ExecutionEngineService[F].commit _)
                     ).map { sequentialResult =>
                         DeploysCheckpoint(
                           preStateHash,
                           sequentialResult.postStateHash,
                           sequentialResult.bondedValidators,
                           deploysForBlock ++ sequentialResult.deploysForBlock,
                           protocolVersion
                         )
                       }
                       .timer("commitDeploysSequentially")
                 )
    } yield result
  }

  // Discard deploys that will never be included because they failed some precondition.
  // If we traveled back on the DAG (due to orphaned block) and picked a deploy to be included
  // in the past of the new fork, it wouldn't hit this as the nonce would be what we expect.
  // Then if a block gets finalized and we remove the deploys it contains, and _then_ one of them
  // turns up again for some reason, we'll treat it again as a pending deploy and try to include it.
  // At that point the EE will discard it as the nonce is in the past and we'll drop it here.
  def handleInvalidDeploys[F[_]: MonadThrowable: DeployStorage: Log: Metrics](
      invalidDeploys: List[PreconditionFailure]
  ): F[Unit] = Metrics[F].timer("handleInvalidDeploys") {
    for {
      _ <- invalidDeploys.traverse { d =>
            // Log precondition failures as we will be getting rid of them.
            Log[F].warn(
              s"${PrettyPrinter.buildString(d.deploy.deployHash) -> "deploy"} failed precondition error: ${d.errorMessage}"
            )
          }
      _ <- DeployStorageWriter[F]
            .markAsDiscarded(
              invalidDeploys.map(pf => (pf.deploy, pf.errorMessage))
            ) whenA invalidDeploys.nonEmpty
    } yield ()
  }

  /** Executes set of deploys using provided `eeExec` function.
    *
    * @return List of execution results.
    */
  def eeExecuteDeploys[F[_]: MonadThrowable](
      prestate: StateHash,
      blocktime: Long,
      deploys: Seq[Deploy],
      protocolVersion: state.ProtocolVersion
  )(eeExec: EEExecFun[F]): F[List[ProcessedDeployResult]] =
    for {
      eeDeploys <- MonadThrowable[F].fromTry(
                    deploys.toList.traverse(ProtoUtil.deployDataToEEDeploy[F](_))
                  )
      results <- eeExec(prestate, blocktime, eeDeploys, protocolVersion).rethrow
      _ <- MonadThrowable[F]
            .raiseError[List[ProcessedDeployResult]](
              SmartContractEngineError(
                s"Unexpected number of results (${results.size} vs ${deploys.size} expected."
              )
            )
            .whenA(results.size != deploys.size)
    } yield zipDeploysResults(deploys, results)

  /** Chooses a set of commuting effects.
    *
    * Set is a FIFO one - the very first commuting effect will be chosen,
    * meaning even if there's a larger set of commuting effect later in that list
    * they will be rejected.
    *
    * @param deployEffects List of effects that deploy made on the GlobalState.
    *
    * @return List of deploy effects that commute.
    */
  //TODO: Logic for picking the commuting group? Prioritize highest revenue? Try to include as many deploys as possible?
  def findCommutingEffects(
      deployEffects: Seq[DeployEffects]
  ): List[DeployEffects] =
    deployEffects match {
      case Nil => Nil
      case list =>
        val (result, _) =
          list.foldLeft((List.empty[DeployEffects] -> Map.empty[state.Key, Op])) {
            case (unchanged @ (acc, totalOps), next) =>
              val ops = Op.fromIpcEntry(next.effects.opMap)
              if (totalOps ~ ops)
                (next :: acc, totalOps + ops)
              else
                unchanged
          }
        result
    }

  def zipDeploysResults(
      deploys: Seq[Deploy],
      results: Seq[DeployResult]
  ): List[ProcessedDeployResult] =
    deploys.zip(results).map((ProcessedDeployResult.apply _).tupled).toList

  def unzipEffectsAndDeploys(
      commutingEffects: Seq[DeployEffects],
      stage: Int = 0
  ): Seq[(Block.ProcessedDeploy, Seq[TransformEntry])] =
    commutingEffects map {
      case ExecutionSuccessful(deploy, effects, cost) =>
        Block.ProcessedDeploy(
          Some(deploy),
          cost,
          false,
          stage = stage
        ) -> effects.transformMap
      case ExecutionError(deploy, error, effects, cost) =>
        Block.ProcessedDeploy(
          Some(deploy),
          cost,
          true,
          utils.deployErrorsShow.show(error),
          stage = stage
        ) -> effects.transformMap
    }

  def isGenesisLike[F[_]: ExecutionEngineService](block: Block): Boolean =
    block.getHeader.parentHashes.isEmpty &&
      block.getHeader.getState.preStateHash == ExecutionEngineService[F].emptyStateHash

  /** Runs deploys from the block and returns the effects they make.
    *
    * @param block Block to run.
    * @param prestate prestate hash of the GlobalState on top of which to run deploys.
    * @return Effects of running deploys from the block
    */
  def effectsForBlock[F[_]: Sync: ExecutionEngineService: BlockStorage: CasperLabsProtocol](
      block: Block,
      prestate: StateHash
  ): F[BlockEffects] = {
    val deploysGrouped = ProtoUtil.deploys(block)
    val blocktime      = block.getHeader.timestamp

    if (block.isGenesisLike) {
      // The new Genesis definition is that there's a chain spec that everyone's supposed to
      // execute on their own and they aren't passed around to be executed.
      BlockStorage[F].get(block.blockHash).flatMap {
        case None =>
          MonadThrowable[F].raiseError(
            new IllegalStateException(
              s"Block ${PrettyPrinter.buildString(block.blockHash)} looks like a Genesis based on a ChainSpec but it cannot be found in storage."
            )
          )
        case Some(genesis) =>
          BlockEffects(genesis.blockEffects.map(se => (se.stage, se.effects)).toMap).pure[F]
      }
    } else {
      for {
        protocolVersion <- CasperLabsProtocol[F].protocolFromBlock(block)
        effectsRef      <- Ref[F].of(Map.empty[Int, Seq[TransformEntry]])
        _ <- deploysGrouped.toList.foldLeftM(prestate) {
              case (preStateHash, (stage, deploys)) =>
                val eeCommitCaptureEffects: EECommitFun[F] =
                  (preState, transforms, protocol) =>
                    for {
                      _ <- effectsRef.update(_.updated(stage, transforms))
                      commitResult <- ExecutionEngineService[F].commit(
                                       preState,
                                       transforms,
                                       protocol
                                     )
                    } yield commitResult

                execCommitParDeploys[F](
                  stage,
                  preStateHash,
                  blocktime,
                  protocolVersion,
                  CommutingDeploys(deploys.map(_.deploy.get))
                )(
                  ExecutionEngineService[F].exec _,
                  eeCommitCaptureEffects
                ).flatMap {
                    case Left(_) =>
                      MonadThrowable[F].raiseError[DeploysCheckpoint](
                        new IllegalArgumentException(
                          s"Block ${PrettyPrinter.buildString(block.blockHash)} contained deploys that failed with PreconditionFailure error."
                        )
                      )
                    case Right(dc) => dc.pure[F]
                  }
                  .map(_.postStateHash)
            }

        blockEffects <- effectsRef.get.map(BlockEffects(_))
      } yield blockEffects
    }
  }

  /** Compute the post state hash of the merged state, which is to be the pre-state
    * of the block we are creating or validating, by committing all the changes of
    * the secondary parents they made on top of the main parent. Then apply any
    * upgrades which were activated at the point we are building the next block on.
    */
  def computePrestate[F[_]: MonadThrowable: ExecutionEngineService: Log](
      merged: MergeResult[TransformMap, Block],
      rank: Long, // Rank of the block we are creating on top of the parents; can be way ahead because of justifications.
      upgrades: Seq[ChainSpec.UpgradePoint]
  ): F[StateHash] = {
    val mergedStateHash: F[StateHash] = merged match {
      case MergeResult.EmptyMerge =>
        ExecutionEngineService[F].emptyStateHash.pure[F] //no parents

      case MergeResult.Result(soleParent, _, others) if others.isEmpty =>
        ProtoUtil.postStateHash(soleParent).pure[F] //single parent

      case MergeResult.Result(initParent, nonFirstParentsCombinedEffect, _) => //multiple parents
        val prestate        = ProtoUtil.postStateHash(initParent)
        val protocolVersion = initParent.getHeader.getProtocolVersion
        ExecutionEngineService[F]
          .commit(prestate, nonFirstParentsCombinedEffect, protocolVersion)
          .rethrow
          .map(_.postStateHash)
    }

    mergedStateHash.flatMap { postStateHash =>
      if (merged.parents.nonEmpty) {
        val protocolVersion = merged.parents.head.getHeader.getProtocolVersion
        val maxRank         = merged.parents.map(_.getHeader.rank).max

        val activatedUpgrades = upgrades.filter { u =>
          maxRank < u.getActivationPoint.rank && u.getActivationPoint.rank <= rank
        }
        activatedUpgrades.toList.foldLeftM(postStateHash) {
          case (postStateHash, upgrade) =>
            Log[F].info(s"Applying upgrade ${upgrade.getProtocolVersion}") *>
              ExecutionEngineService[F]
                .upgrade(postStateHash, upgrade, protocolVersion)
                .rethrow
                .map(_.postStateHash)
          // NOTE: We are dropping the effects here, so they won't be part of the block.
        }
      } else {
        postStateHash.pure[F]
      }
    }
  }

  type TransformMap = Seq[TransformEntry]
  implicit val TransformMapMonoid: Monoid[TransformMap] = new Monoid[TransformMap] {
    def combine(t1: TransformMap, t2: TransformMap): TransformMap = t1 ++ t2

    def empty: TransformMap = Nil
  }

  sealed trait MergeResult[+T, +A] {
    self =>
    def parents: Vector[A] = self match {
      case MergeResult.EmptyMerge            => Vector.empty[A]
      case MergeResult.Result(head, _, tail) => head +: tail
    }

    def transform: Option[T] = self match {
      case MergeResult.EmptyMerge      => None
      case MergeResult.Result(_, t, _) => Some(t)
    }
  }

  object MergeResult {

    case object EmptyMerge extends MergeResult[Nothing, Nothing]

    case class Result[T, A](
        firstParent: A,
        nonFirstParentsCombinedEffect: T,
        nonFirstParents: Vector[A]
    ) extends MergeResult[T, A]

    def empty[T, A]: MergeResult[T, A] = EmptyMerge

    def result[T, A](
        firstParent: A,
        nonFirstParentsCombinedEffect: T,
        nonFirstParents: Vector[A]
    ): MergeResult[T, A] = Result(firstParent, nonFirstParentsCombinedEffect, nonFirstParents)
  }

  /** Computes the largest commuting sub-set of blocks from the `candidateParents` along with an effect which
    * can be used to find the combined post-state of those commuting blocks.
    *
    * @tparam F effect type (a la tagless final)
    * @tparam T type for transforms (i.e. effects deploys create when executed)
    * @tparam A type for "blocks". Order must be a topological order of the DAG blocks form
    * @tparam K type for keys specifying what a transform is applied to (equal to state.Key in production)
    * @param candidates "blocks" to attempt to merge
    * @param parents    function for computing the parents of a "block" (equal to _.parents in production)
    * @param effect     function for computing the transforms of a block (looks up the transaforms from the blockstorage in production)
    * @param toOps      function for converting transforms into the OpMap, which is then used for commutativity checking
    * @return an instance of the MergeResult class. It is either an `EmptyMerge` (which only happens when `candidates` is empty)
    *         or a `Result` which contains the "first parent" (the who's post-state will be used to apply the effects to obtain
    *         the merged post-state), the combined effect of all parents apart from the first (i.e. this effect will give
    *         the combined post state for all chosen commuting blocks when applied to the post-state of the first chosen block),
    *         and the list of the additional chosen parents apart from the first.
    */
  def abstractMerge[F[_]: Monad, T: Monoid, A: Ordering, K](
      candidates: NonEmptyList[A],
      parents: A => F[List[A]],
      effect: A => F[Option[T]],
      toOps: T => OpMap[K]
  ): F[MergeResult.Result[T, A]] = {
    val n               = candidates.length
    val candidateVector = candidates.toList.toVector

    def netEffect(blocks: Vector[A]): F[T] =
      blocks
        .traverse(block => effect(block))
        .map(va => Foldable[Vector].fold(va.flatten))

    if (n == 1) {
      MergeResult.Result[T, A](candidates.head, Monoid[T].empty, Vector.empty).pure[F]
    } else
      for {
        uncommonAncestors <- DagOperations
                              .abstractUncommonAncestors[F, A](candidateVector, parents)

        // collect uncommon ancestors based on which candidate they are an ancestor of
        groups = uncommonAncestors
          .foldLeft(Vector.fill(n)(Vector.empty[A]).zipWithIndex) {
            case (acc, (block, ancestry)) =>
              acc.map {
                case (group, index) =>
                  val newGroup = if (ancestry.contains(index)) group :+ block else group
                  newGroup -> index
              }
          } // sort in topological order to combine effects in the right order
          .map { case (group, _) => group.sorted }

        // always choose the first parent
        initChosen      = Vector(0)
        initChosenGroup = groups(0)
        // effects chosen apart from the first parent
        initNonFirstEffect = Monoid[T].empty

        chosen <- (1 until n).toList
                   .foldM[F, (Vector[Int], Vector[A], T)](
                     (initChosen, initChosenGroup, initNonFirstEffect)
                   ) {
                     case (
                         unchanged @ (chosenSet, chosenGroup, chosenNonFirstEffect),
                         candidate
                         ) =>
                       val candidateGroup = groups(candidate)
                         .filterNot { // remove ancestors already included in the chosenSet
                           block =>
                             val ancestry = uncommonAncestors(block)
                             chosenSet.exists(i => ancestry.contains(i))
                         }

                       val chosenEffectF = netEffect(
                         // remove ancestors already included in the candidate itself
                         chosenGroup.filterNot { block =>
                           uncommonAncestors(block).contains(candidate)
                         }
                       )

                       // if candidate commutes with chosen set, then included, otherwise do not include it
                       chosenEffectF.flatMap { chosenEffect =>
                         netEffect(candidateGroup).map { candidateEffect =>
                           if (toOps(chosenEffect) ~ toOps(candidateEffect))
                             (
                               chosenSet :+ candidate,
                               chosenGroup ++ candidateGroup,
                               Monoid[T].combine(chosenNonFirstEffect, candidateEffect)
                             )
                           else
                             unchanged
                         }
                       }
                   }
        // The effect we return is the one which would be applied onto the first parent's
        // post-state, so we do not include the first parent in the effect.
        (chosenParents, _, nonFirstEffect) = chosen
        candidateVector                    = candidates.toList.toVector
        blocks                             = chosenParents.map(i => candidateVector(i))
        // We only keep secondary parents which are not related in any way to other candidates.
        // Note: a block is not found in `uncommonAncestors` if it is common to all
        // candidates, so we cannot assume it is present.
        nonFirstParents = blocks.tail
          .filter(block => uncommonAncestors.get(block).fold(false)(_.size == 1))
      } yield MergeResult.Result[T, A](blocks.head, nonFirstEffect, nonFirstParents)
  }

  def merge[F[_]: MonadThrowable: BlockStorage: Metrics](
      candidateParentBlocks: NonEmptyList[Block],
      dag: DagRepresentation[F]
  ): F[MergeResult.Result[TransformMap, Block]] = {

    // TODO: These things should be part of the validation.
    def getParent(child: ByteString, parent: ByteString): F[Message.Block] =
      dag.lookup(parent) flatMap {
        case Some(block: Message.Block) =>
          block.pure[F]
        case Some(_: Message.Ballot) =>
          MonadThrowable[F].raiseError(
            new IllegalStateException(
              s"${PrettyPrinter.buildString(child)} has a ballot as a parent: ${PrettyPrinter.buildString(parent)}"
            )
          )
        case None =>
          MonadThrowable[F].raiseError(
            new IllegalStateException(
              s"${PrettyPrinter.buildString(child)} has missing parent: ${PrettyPrinter.buildString(parent)}"
            )
          )
      }

    def getParents(msg: Message): F[List[Message.Block]] =
      msg match {
        case block: Message.Block =>
          block.parents.toList.traverse(getParent(msg.messageHash, _))

        case ballot: Message.Ballot =>
          getParent(msg.messageHash, ballot.parentBlock).map(List(_))
      }

    def getEffects(blockMeta: Message.Block): F[Option[TransformMap]] =
      BlockStorage[F]
        .get(blockMeta.messageHash)
        .map(_.map { blockWithTransforms =>
          val blockHash  = blockWithTransforms.getBlockMessage.blockHash
          val transforms = blockWithTransforms.blockEffects.flatMap(_.effects)
          // To avoid the possibility of duplicate deploys, pretend that a deploy
          // writes to its own deploy hash, to generate conflicts between blocks
          // that have the same deploy in their bodies.
          val deployHashTransforms =
            blockWithTransforms.getBlockMessage.getBody.deploys.map(_.getDeploy).map {
              deploy =>
                val key: Key     = Key(Key.Value.Hash(Key.Hash(deploy.deployHash)))
                val tyU8: CLType = CLType(CLType.Variants.SimpleType(CLType.Simple.U8))
                val tyByteArray32: CLType =
                  CLType(CLType.Variants.FixedListType(CLType.FixedList(Some(tyU8), 32)))
                val blockHashValue: CLValue = CLValue(Some(tyByteArray32), blockHash)
                val transform = Transform(
                  Transform.TransformInstance
                    .Write(TransformWrite().withValue(StoredValue().withClValue(blockHashValue)))
                )
                TransformEntry().withKey(key).withTransform(transform)
            }

          transforms ++ deployHashTransforms
        })

    def toOps(t: TransformMap): OpMap[state.Key] = Op.fromTransforms(t)

    import io.casperlabs.shared.Sorting.messageSummaryOrdering
    for {
      candidateMessages <- MonadThrowable[F]
                            .fromTry(
                              candidateParentBlocks.toList.traverse(Message.fromBlock(_))
                            )
      candidateParents <- candidateMessages
                           .traverse {
                             case block: Message.Block =>
                               block.pure[F]
                             case ballot: Message.Ballot =>
                               getParent(ballot.messageHash, ballot.parentBlock)
                           }
                           .map(ps => NonEmptyList.fromListUnsafe(ps.distinct))
      merged <- abstractMerge[F, TransformMap, Message.Block, state.Key](
                 candidateParents,
                 getParents,
                 getEffects,
                 toOps
               )
      // TODO: Aren't these parents already in `candidateParentBlocks`?
      blocks <- merged.parents.traverse(block => ProtoUtil.unsafeGetBlock[F](block.messageHash))
      _      <- Metrics[F].record("mergedBlocks", blocks.size.toLong)
    } yield MergeResult.Result(blocks.head, merged.nonFirstParentsCombinedEffect, blocks.tail)
  }
}
