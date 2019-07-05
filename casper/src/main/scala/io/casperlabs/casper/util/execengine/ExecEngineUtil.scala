package io.casperlabs.casper.util.execengine

import cats.effect.Sync
import cats.implicits._
import cats.kernel.Monoid
import cats.{Foldable, Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockMetadata, BlockStore}
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.casper.util.ProtoUtil.blockNumber
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.execengine.Op.{OpMap, OpMapAddComm}
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, DagOperations, ProtoUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.ipc._
import io.casperlabs.casper.consensus.state
import io.casperlabs.models.{DeployResult => _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

case class DeploysCheckpoint(
    preStateHash: StateHash,
    postStateHash: StateHash,
    bondedValidators: Seq[io.casperlabs.casper.consensus.Bond],
    deploysForBlock: Seq[Block.ProcessedDeploy],
    invalidNonceDeploys: Seq[InvalidNonceDeploy],
    deploysToDiscard: Seq[PreconditionFailure],
    protocolVersion: state.ProtocolVersion
)

object ExecEngineUtil {
  type StateHash = ByteString

  case class InvalidDeploys(
      invalidNonceDeploys: List[InvalidNonceDeploy],
      preconditionFailures: List[PreconditionFailure]
  )

  def computeDeploysCheckpoint[F[_]: MonadThrowable: BlockStore: Log: ExecutionEngineService](
      merged: MergeResult[TransformMap, Block],
      deploys: Seq[Deploy],
      blocktime: Long,
      protocolVersion: state.ProtocolVersion
  ): F[DeploysCheckpoint] =
    for {
      preStateHash <- computePrestate[F](merged)
      processedDeploys <- processDeploys[F](
                           preStateHash,
                           blocktime,
                           deploys,
                           protocolVersion,
                           ignoreGasCount = true
                         )
      processedDeployResults = zipDeploysResults(deploys, processedDeploys).toList
      invalidDeploys <- processedDeployResults.foldM[F, InvalidDeploys](InvalidDeploys(Nil, Nil)) {
                         case (acc, d: InvalidNonceDeploy) =>
                           acc.copy(invalidNonceDeploys = d :: acc.invalidNonceDeploys).pure[F]
                         case (acc, d: PreconditionFailure) =>
                           // Log precondition failures as we will be getting rid of them.
                           Log[F].warn(
                             s"Deploy ${PrettyPrinter.buildString(d.deploy.deployHash)} failed precondition error: ${d.errorMessage}"
                           ) as {
                             acc.copy(preconditionFailures = d :: acc.preconditionFailures)
                           }
                         case (acc, _) =>
                           acc.pure[F]
                       }
      deployEffects                 = findCommutingEffects(processedDeployResults)
      (deploysForBlock, transforms) = ExecEngineUtil.unzipEffectsAndDeploys(deployEffects).unzip
      commitResult                  <- ExecutionEngineService[F].commit(preStateHash, transforms.flatten).rethrow
      //TODO: Remove this logging at some point
      msgBody = transforms.flatten
        .map(t => {
          val k    = PrettyPrinter.buildString(t.key.get)
          val tStr = PrettyPrinter.buildString(t.transform.get)
          s"$k :: $tStr"
        })
        .mkString("\n")
      _ <- Log[F]
            .info(s"Block created with effects:\n$msgBody")
    } yield DeploysCheckpoint(
      preStateHash,
      commitResult.postStateHash,
      commitResult.bondedValidators,
      deploysForBlock,
      invalidDeploys.invalidNonceDeploys,
      invalidDeploys.preconditionFailures,
      protocolVersion
    )

  private def processDeploys[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      prestate: StateHash,
      blocktime: Long,
      deploys: Seq[Deploy],
      protocolVersion: state.ProtocolVersion,
      ignoreGasCount: Boolean
  ): F[Seq[DeployResult]] =
    ExecutionEngineService[F]
      .exec(
        prestate,
        blocktime,
        deploys.map(ProtoUtil.deployDataToEEDeploy),
        protocolVersion,
        ignoreGasCount
      )
      .rethrow

  private def processGenesisDeploys[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      deploys: Seq[Deploy],
      protocolVersion: state.ProtocolVersion
  ): F[GenesisResult] =
    ExecutionEngineService[F]
      .runGenesis(deploys.map(ProtoUtil.deployDataToEEDeploy), protocolVersion)
      .rethrow

  //TODO: Logic for picking the commuting group? Prioritize highest revenue? Try to include as many deploys as possible?
  def findCommutingEffects(
      deployEffects: Seq[ProcessedDeployResult]
  ): Seq[ProcessedDeployResult] = {
    // All the deploys that do not change the global state in a way that can conflict with others:
    // which can be only`ExecutionError` now as `InvalidNonce` and `PreconditionFailure` has been
    // filtered out when creating block and when we're validating block it shouldn't include those either.
    val (conflictFree, mergeCandidates) =
      deployEffects.partition(!_.isInstanceOf[ExecutionSuccessful])

    val nonConflicting = mergeCandidates match {
      case Nil => List.empty[ProcessedDeployResult]
      case list =>
        val (result, _) =
          list.foldLeft(List.empty[ProcessedDeployResult] -> Map.empty[state.Key, Op]) {
            case (unchanged @ (acc, totalOps), next @ ExecutionSuccessful(_, effects, _)) =>
              val ops = Op.fromIpcEntry(effects.opMap)
              if (totalOps ~ ops)
                (next :: acc, totalOps + ops)
              else
                unchanged
            case _ => ???
          }

        result
    }

    // We include errors because we define them as
    // commuting with everything since we will never
    // re-run them (this is a policy decision we have made) and
    // they touch no keys because we rolled back the changes
    nonConflicting ++ conflictFree
  }

  def zipDeploysResults(
      deploys: Seq[Deploy],
      results: Seq[DeployResult]
  ): Seq[ProcessedDeployResult] =
    deploys.zip(results).map((ProcessedDeployResult.apply _).tupled)

  def unzipEffectsAndDeploys(
      commutingEffects: Seq[ProcessedDeployResult]
  ): Seq[(Block.ProcessedDeploy, Seq[TransformEntry])] =
    commutingEffects.collect {
      case ExecutionSuccessful(deploy, effects, cost) =>
        Block.ProcessedDeploy(
          Some(deploy),
          cost,
          false
        ) -> effects.transformMap
      case ExecutionError(deploy, error, effects, cost) =>
        Block.ProcessedDeploy(
          Some(deploy),
          cost,
          true,
          utils.deployErrorsShow.show(error)
        ) -> effects.transformMap
    }

  def isGenesisLike[F[_]: ExecutionEngineService](block: Block): Boolean =
    block.getHeader.parentHashes.isEmpty &&
      block.getHeader.getState.preStateHash == ExecutionEngineService[F].emptyStateHash

  /** Runs deploys from the block and returns the effects they make.
    *
    * @param block Block to run.
    * @param prestate prestate hash of the GlobalState on top of which to run deploys.
    * @return Effects of running deploys from the block.
    */
  def effectsForBlock[F[_]: MonadThrowable: BlockStore: ExecutionEngineService](
      block: Block,
      prestate: StateHash
  ): F[Seq[TransformEntry]] = {
    val deploys         = ProtoUtil.deploys(block).flatMap(_.deploy)
    val protocolVersion = CasperLabsProtocolVersions.thresholdsVersionMap.fromBlock(block)
    val blocktime       = block.getHeader.timestamp

    if (isGenesisLike(block)) {
      for {
        genesisResult <- processGenesisDeploys[F](deploys, protocolVersion)
        transformMap  = genesisResult.getEffect.transformMap
      } yield transformMap
    } else {
      for {
        processedDeploys <- processDeploys[F](
                             prestate,
                             blocktime,
                             deploys,
                             protocolVersion,
                             ignoreGasCount = false
                           )
        deployEffects = zipDeploysResults(deploys, processedDeploys)
        transformMap = (findCommutingEffects _ andThen unzipEffectsAndDeploys)(deployEffects)
          .flatMap(_._2)
      } yield transformMap
    }
  }

  def computePrestate[F[_]: MonadError[?[_], Throwable]: ExecutionEngineService](
      merged: MergeResult[TransformMap, Block]
  ): F[StateHash] = merged match {
    case MergeResult.EmptyMerge => ExecutionEngineService[F].emptyStateHash.pure[F] //no parents
    case MergeResult.Result(soleParent, _, others) if others.isEmpty =>
      ProtoUtil.postStateHash(soleParent).pure[F] //single parent
    case MergeResult.Result(initParent, nonFirstParentsCombinedEffect, _) => //multiple parents
      val prestate = ProtoUtil.postStateHash(initParent)
      MonadError[F, Throwable]
        .rethrow(
          ExecutionEngineService[F].commit(prestate, nonFirstParentsCombinedEffect)
        )
        .map(_.postStateHash)
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
    * @param effect     function for computing the transforms of a block (looks up the transaforms from the blockstore in production)
    * @param toOps      function for converting transforms into the OpMap, which is then used for commutativity checking
    * @return an instance of the MergeResult class. It is either an `EmptyMerge` (which only happens when `candidates` is empty)
    *         or a `Result` which contains the "first parent" (the who's post-state will be used to apply the effects to obtain
    *         the merged post-state), the combined effect of all parents apart from the first (i.e. this effect will give
    *         the combined post state for all chosen commuting blocks when applied to the post-state of the first chosen block),
    *         and the list of the additional chosen parents apart from the first.
    */
  def abstractMerge[F[_]: Monad, T: Monoid, A: Ordering, K](
      candidates: IndexedSeq[A],
      parents: A => F[List[A]],
      effect: A => F[Option[T]],
      toOps: T => OpMap[K]
  ): F[MergeResult[T, A]] = {
    val n = candidates.length

    def netEffect(blocks: Vector[A]): F[T] =
      blocks
        .traverse(block => effect(block))
        .map(va => Foldable[Vector].fold(va.flatten))

    if (n == 0) {
      MergeResult.empty[T, A].pure[F]
    } else if (n == 1) {
      MergeResult.result[T, A](candidates.head, Monoid[T].empty, Vector.empty).pure[F]
    } else
      for {
        uncommonAncestors <- DagOperations.abstractUncommonAncestors[F, A](candidates, parents)

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
        blocks                             = chosenParents.map(i => candidates(i))
      } yield MergeResult.result[T, A](blocks.head, nonFirstEffect, blocks.tail)
  }

  def merge[F[_]: MonadThrowable: BlockStore](
      candidateParentBlocks: Seq[Block],
      dag: BlockDagRepresentation[F]
  ): F[MergeResult[TransformMap, Block]] = {

    def parents(b: BlockMetadata): F[List[BlockMetadata]] =
      b.parents.traverse(b => dag.lookup(b).map(_.get))

    def effect(block: BlockMetadata): F[Option[TransformMap]] =
      BlockStore[F].getTransforms(block.blockHash)

    def toOps(t: TransformMap): OpMap[state.Key] = Op.fromTransforms(t)

    val candidateParents = candidateParentBlocks.map(BlockMetadata.fromBlock).toVector

    for {
      ordering <- dag.deriveOrdering(0L) // TODO: Replace with an actual starting number
      merged <- {
        implicit val order = ordering
        abstractMerge[F, TransformMap, BlockMetadata, state.Key](
          candidateParents,
          parents,
          effect,
          toOps
        )
      }
      // TODO: Aren't these parents already in `candidateParentBlocks`?
      blocks <- merged.parents.traverse(block => ProtoUtil.unsafeGetBlock[F](block.blockHash))
    } yield merged.transform.fold(MergeResult.empty[TransformMap, Block])(
      MergeResult.result(blocks.head, _, blocks.tail)
    )
  }
}
