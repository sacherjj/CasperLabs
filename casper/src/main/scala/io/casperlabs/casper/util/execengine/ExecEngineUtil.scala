package io.casperlabs.casper.util.execengine

import cats.effect._
import cats.implicits._
import cats.kernel.Monoid
import cats.data.NonEmptyList
import cats.{Foldable, Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.state.CLType.Variants
import io.casperlabs.casper.consensus.state.{CLType, CLValue, Key, StoredValue, Value}
import io.casperlabs.casper.consensus.{state, Block, Deploy}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.execengine.Op.{OpMap, OpMapAddComm}
import io.casperlabs.casper.util.{CasperLabsProtocol, DagOperations, ProtoUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.ipc._
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Message
import io.casperlabs.models.{DeployResult => _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageWriter}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.metrics.implicits._

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
      pdr <- DeploySelection[F].select(
              (preStateHash, blocktime, protocolVersion, deployStream)
            )
      (invalidDeploys, deployEffects) = ProcessedDeployResult.split(pdr)
      _                               <- handleInvalidDeploys[F](invalidDeploys)
      (deploysForBlock, transforms)   = ExecEngineUtil.unzipEffectsAndDeploys(deployEffects).unzip
      commitResult <- ExecutionEngineService[F]
                       .commit(preStateHash, transforms.flatten, protocolVersion)
                       .rethrow
    } yield DeploysCheckpoint(
      preStateHash,
      commitResult.postStateHash,
      commitResult.bondedValidators,
      deploysForBlock,
      protocolVersion
    )
  }

  // Discard deploys that will never be included because they failed some precondition.
  // If we traveled back on the DAG (due to orphaned block) and picked a deploy to be included
  // in the past of the new fork, it wouldn't hit this as the nonce would be what we expect.
  // Then if a block gets finalized and we remove the deploys it contains, and _then_ one of them
  // turns up again for some reason, we'll treat it again as a pending deploy and try to include it.
  // At that point the EE will discard it as the nonce is in the past and we'll drop it here.
  def handleInvalidDeploys[F[_]: MonadThrowable: DeployStorage: Log: Metrics](
      invalidDeploys: List[NoEffectsFailure]
  ): F[Unit] = Metrics[F].timer("handleInvalidDeploys") {
    for {
      invalidDeploys <- invalidDeploys.foldM[F, InvalidDeploys](InvalidDeploys(Nil)) {
                         case (acc, d: PreconditionFailure) =>
                           // Log precondition failures as we will be getting rid of them.
                           Log[F].warn(
                             s"${PrettyPrinter.buildString(d.deploy.deployHash) -> "deploy"} failed precondition error: ${d.errorMessage}"
                           ) as {
                             acc.copy(preconditionFailures = d :: acc.preconditionFailures)
                           }
                       }
      _ <- DeployStorageWriter[F]
            .markAsDiscarded(
              invalidDeploys.preconditionFailures.map(pf => (pf.deploy, pf.errorMessage))
            ) whenA invalidDeploys.preconditionFailures.nonEmpty
    } yield ()
  }

  def processDeploys[F[_]: MonadThrowable: ExecutionEngineService](
      prestate: StateHash,
      blocktime: Long,
      deploys: Seq[Deploy],
      protocolVersion: state.ProtocolVersion
  ): F[Seq[DeployResult]] =
    for {
      eeDeploys <- deploys.toList.traverse(ProtoUtil.deployDataToEEDeploy[F](_))
      results <- ExecutionEngineService[F]
                  .exec(prestate, blocktime, eeDeploys, protocolVersion)
                  .rethrow
    } yield results

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
  ): Seq[ProcessedDeployResult] =
    deploys.zip(results).map((ProcessedDeployResult.apply _).tupled)

  def unzipEffectsAndDeploys(
      commutingEffects: Seq[DeployEffects]
  ): Seq[(Block.ProcessedDeploy, Seq[TransformEntry])] =
    commutingEffects map {
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
    * @return Effects of running deploys from the block
    */
  def effectsForBlock[F[_]: MonadThrowable: ExecutionEngineService: BlockStorage: CasperLabsProtocol](
      block: Block,
      prestate: StateHash
  ): F[Seq[TransformEntry]] = {
    val deploys   = ProtoUtil.deploys(block).flatMap(_.deploy)
    val blocktime = block.getHeader.timestamp

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
          genesis.transformEntry.pure[F]
      }
    } else {
      for {
        protocolVersion <- CasperLabsProtocol[F].protocolFromBlock(block)
        processedDeploys <- processDeploys[F](
                             prestate,
                             blocktime,
                             deploys,
                             protocolVersion
                           )
        deployEffects    = zipDeploysResults(deploys, processedDeploys)
        effectfulDeploys = ProcessedDeployResult.split(deployEffects.toList)._2
        transformMap = unzipEffectsAndDeploys(findCommutingEffects(effectfulDeploys))
          .flatMap(_._2)
      } yield transformMap
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
          val transforms = blockWithTransforms.transformEntry
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
