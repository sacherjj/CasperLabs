package io.casperlabs.casper.util.execengine

import cats.effect.Sync
import cats.implicits._
import cats.kernel.Monoid
import cats.{Foldable, Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockMetadata, BlockStorage, DagRepresentation}
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.execengine.Op.{OpMap, OpMapAddComm}
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, DagOperations, ProtoUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.ipc._
import io.casperlabs.casper.consensus.state
import io.casperlabs.models.{DeployResult => _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion, Value}
import io.casperlabs.casper.deploybuffer.DeployBuffer

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
      invalidNonceDeploys: List[InvalidNonceDeploy],
      preconditionFailures: List[PreconditionFailure]
  )

  def computeDeploysCheckpoint[F[_]: Sync: DeployBuffer: Log: ExecutionEngineService: DeploySelection](
      merged: MergeResult[TransformMap, Block],
      hashes: Set[DeployHash],
      blocktime: Long,
      protocolVersion: state.ProtocolVersion
  ): F[DeploysCheckpoint] =
    for {
      preStateHash <- computePrestate[F](merged)
      deployStream = fs2.Stream
        .fromIterator[F, DeployHash](hashes.toIterator)
        .chunkLimit(10) // TODO: from config?
        .flatMap(batch => fs2.Stream.eval(DeployBuffer[F].getByHashes(batch.toList)))
      deployEffects <- DeploySelection[F].select(
                        (preStateHash, blocktime, protocolVersion, deployStream)
                      )
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
      protocolVersion
    )

  // Discard deploys that will never be included because they failed some precondition.
  // If we traveled back on the DAG (due to orphaned block) and picked a deploy to be included
  // in the past of the new fork, it wouldn't hit this as the nonce would be what we expect.
  // Then if a block gets finalized and we remove the deploys it contains, and _then_ one of them
  // turns up again for some reason, we'll treat it again as a pending deploy and try to include it.
  // At that point the EE will discard it as the nonce is in the past and we'll drop it here.
  def handleInvalidDeploys[F[_]: MonadThrowable: DeployBuffer: Log](
      invalidDeploys: List[NoEffectsFailure]
  ): F[Unit] =
    for {
      invalidDeploys <- invalidDeploys.foldM[F, InvalidDeploys](InvalidDeploys(Nil, Nil)) {
                         case (acc, d: InvalidNonceDeploy) =>
                           acc.copy(invalidNonceDeploys = d :: acc.invalidNonceDeploys).pure[F]
                         case (acc, d: PreconditionFailure) =>
                           // Log precondition failures as we will be getting rid of them.
                           Log[F].warn(
                             s"Deploy ${PrettyPrinter.buildString(d.deploy.deployHash)} failed precondition error: ${d.errorMessage}"
                           ) as {
                             acc.copy(preconditionFailures = d :: acc.preconditionFailures)
                           }
                       }
      // We don't have to put InvalidNonce deploys back to the buffer,
      // as by default buffer is cleared when deploy gets included in
      // the finalized block. If that strategy ever changes, we will have to
      // put them back into the buffer explicitly.
      _ <- DeployBuffer[F]
            .markAsDiscarded(invalidDeploys.preconditionFailures.map(_.deploy)) whenA invalidDeploys.preconditionFailures.nonEmpty
    } yield ()

  def processDeploys[F[_]: MonadThrowable: ExecutionEngineService](
      prestate: StateHash,
      blocktime: Long,
      deploys: Seq[Deploy],
      protocolVersion: state.ProtocolVersion
  ): F[Seq[DeployResult]] =
    ExecutionEngineService[F]
      .exec(prestate, blocktime, deploys.map(ProtoUtil.deployDataToEEDeploy), protocolVersion)
      .rethrow

  private def processGenesisDeploys[F[_]: MonadError[?[_], Throwable]: BlockStorage: ExecutionEngineService](
      deploys: Seq[Deploy],
      protocolVersion: state.ProtocolVersion
  ): F[GenesisResult] =
    ExecutionEngineService[F]
      .runGenesis(deploys.map(ProtoUtil.deployDataToEEDeploy), protocolVersion)
      .rethrow

  /** Chooses a set of commuting effects.
    *
    * Set is a FIFO one - the very first commuting effect will be chosen,
    * meaning even if there's a larger set of commuting effect later in that list
    * they will be rejected.
    *
    * @param deployEffects List of effects that deploy made on the GlobalState.
    * @param init Initial set of deploy effects with which the rest of input `deployEffects`
    *             has to commute with.
    *
    * @return List of deploy effects that commute.
    */
  //TODO: Logic for picking the commuting group? Prioritize highest revenue? Try to include as many deploys as possible?
  def findCommutingEffects(
      deployEffects: Seq[DeployEffects],
      init: (List[DeployEffects], OpMap[state.Key]) = (List.empty, Map.empty)
  ): List[DeployEffects] =
    deployEffects match {
      case Nil => init._1
      case list =>
        val (result, _) =
          list.foldLeft(init) {
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
  def effectsForBlock[F[_]: MonadThrowable: BlockStorage: ExecutionEngineService](
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
                             protocolVersion
                           )
        deployEffects    = zipDeploysResults(deploys, processedDeploys)
        effectfulDeploys = ProcessedDeployResult.split(deployEffects.toList)._2
        transformMap = unzipEffectsAndDeploys(findCommutingEffects(effectfulDeploys))
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
    * @param effect     function for computing the transforms of a block (looks up the transaforms from the blockstorage in production)
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

  def merge[F[_]: MonadThrowable: BlockStorage](
      candidateParentBlocks: Seq[Block],
      dag: DagRepresentation[F]
  ): F[MergeResult[TransformMap, Block]] = {

    def parents(b: BlockMetadata): F[List[BlockMetadata]] =
      b.parents.traverse(b => dag.lookup(b).map(_.get))

    def effect(blockMeta: BlockMetadata): F[Option[TransformMap]] =
      BlockStorage[F]
        .get(blockMeta.blockHash)
        .map(_.map { blockWithTransforms =>
          val blockHash  = blockWithTransforms.getBlockMessage.blockHash
          val transforms = blockWithTransforms.transformEntry
          // To avoid the possibility of duplicate deploys, pretend that a deploy
          // writes to its own deploy hash, to generate conflicts between blocks
          // that have the same deploy in their bodies.
          val deployHashTransforms =
            blockWithTransforms.getBlockMessage.getBody.deploys.map(_.getDeploy).map { deploy =>
              val k = Key(Key.Value.Hash(Key.Hash(deploy.deployHash)))
              val t = Transform(
                Transform.TransformInstance.Write(
                  TransformWrite().withValue(Value(Value.Value.BytesValue(blockHash)))
                )
              )
              TransformEntry().withKey(k).withTransform(t)
            }

          transforms ++ deployHashTransforms
        })

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
