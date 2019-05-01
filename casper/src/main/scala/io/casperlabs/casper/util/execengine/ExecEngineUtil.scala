package io.casperlabs.casper.util.execengine

import cats.effect.Sync
import cats.implicits._
import cats.{Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper._
import io.casperlabs.casper.protocol.{BlockMessage, DeployData, ProcessedDeploy}
import io.casperlabs.casper.util.ProtoUtil.blockNumber
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.ipc
import io.casperlabs.ipc._
import io.casperlabs.models.{DeployResult => _, _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

import scala.collection.immutable.BitSet

import Op.{OpMap, OpMapAddComm}

case class DeploysCheckpoint(
    preStateHash: StateHash,
    postStateHash: StateHash,
    deploysForBlock: Seq[ProcessedDeploy],
    blockNumber: Long
)

object ExecEngineUtil {
  type StateHash = ByteString

  def computeDeploysCheckpoint[F[_]: MonadError[?[_], Throwable]: BlockStore: Log: ExecutionEngineService](
      parents: Seq[BlockMessage],
      deploys: Seq[DeployData],
      combinedEffect: TransformMap // effect used to obtain combined post-state of all parents
  ): F[DeploysCheckpoint] =
    for {
      processedHash <- processDeploys[F](
                        parents,
                        combinedEffect,
                        deploys
                      )
      (preStateHash, processedDeploys) = processedHash
      deployEffects                    = processedDeployEffects(deploys zip processedDeploys)
      commutingEffects                 = findCommutingEffects(deployEffects)
      deploysForBlock = deployEffects.collect {
        case (deploy, Some((_, cost))) => {
          protocol.ProcessedDeploy(
            Some(deploy),
            cost,
            false
          )
        }
      }
      transforms = commutingEffects.unzip._1.flatMap(_.transformMap)
      postStateHash <- MonadError[F, Throwable].rethrow(
                        ExecutionEngineService[F].commit(preStateHash, transforms)
                      )
      maxBlockNumber = parents.foldLeft(-1L) {
        case (acc, b) => math.max(acc, blockNumber(b))
      }
      number = maxBlockNumber + 1
      msgBody = transforms
        .map(t => {
          val k    = PrettyPrinter.buildString(t.key.get)
          val tStr = PrettyPrinter.buildString(t.transform.get)
          s"$k :: $tStr"
        })
        .mkString("\n")
      _ <- Log[F]
            .info(s"Block #$number created with effects:\n$msgBody")
    } yield DeploysCheckpoint(preStateHash, postStateHash, deploysForBlock, number)

  def processDeploys[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      parents: Seq[BlockMessage],
      combinedEffect: TransformMap, // effect used to obtain combined post-state of all parents
      deploys: Seq[DeployData]
  ): F[(StateHash, Seq[DeployResult])] =
    for {
      prestate <- computePrestate[F](parents.toList, combinedEffect)
      ds       = deploys.map(ProtoUtil.deployDataToEEDeploy)
      result   <- MonadError[F, Throwable].rethrow(ExecutionEngineService[F].exec(prestate, ds))
    } yield (prestate, result)

  /** Produce effects for each processed deploy. */
  def processedDeployEffects(
      deployResults: Seq[(DeployData, DeployResult)]
  ): Seq[(DeployData, Option[(ExecutionEffect, Long)])] =
    deployResults.map {
      case (deploy, DeployResult(_, DeployResult.Result.Empty)) =>
        deploy -> None //This should never happen either
      case (deploy, DeployResult(_, DeployResult.Result.Error(_))) =>
        deploy -> None //We should not be ignoring error cost
      case (deploy, DeployResult(cost, DeployResult.Result.Effects(eff))) =>
        deploy -> Some((eff, cost))
    }

  //TODO: actually find which ones commute
  //TODO: How to handle errors?
  def findCommutingEffects(
      deployEffects: Seq[(DeployData, Option[(ExecutionEffect, Long)])]
  ): Seq[(ExecutionEffect, Long)] =
    deployEffects.collect {
      case (_, Some((eff, cost))) => (eff, cost)
    }

  def effectsForBlock[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      block: BlockMessage,
      combinedEffect: TransformMap,
      dag: BlockDagRepresentation[F]
  ): F[(StateHash, Seq[TransformEntry])] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](block)
      deploys = ProtoUtil.deploys(block)
      processedHash <- processDeploys[F](
                        parents,
                        combinedEffect,
                        deploys.flatMap(_.deploy)
                      )
      (prestate, processedDeploys) = processedHash
      deployEffects                = processedDeployEffects(deploys.map(_.getDeploy) zip processedDeploys)
      transformMap                 = findCommutingEffects(deployEffects).unzip._1.flatMap(_.transformMap)
    } yield (prestate, transformMap)

  private def computePrestate[F[_]: MonadError[?[_], Throwable]: ExecutionEngineService](
      parents: List[BlockMessage],
      combinedEffect: TransformMap // effect used to obtain combined post-state of all parents
  ): F[StateHash] = parents match {
    case Nil => ExecutionEngineService[F].emptyStateHash.pure[F] //no parents
    case soleParent :: Nil =>
      ProtoUtil.postStateHash(soleParent).pure[F] //single parent
    case initParent :: _ => //multiple parents
      val prestate = ProtoUtil.postStateHash(initParent)
      MonadError[F, Throwable].rethrow(
        ExecutionEngineService[F].commit(prestate, combinedEffect)
      )
  }

  type TransformMap = Seq[TransformEntry]

  /** Computes the largest commuting sub-set of blocks from the `candidateParents` along with an effect which
    * can be used to find the combined post-state of those commuting blocks.
    * @param candidateParents blocks to attempt to merge
    * @return a tuple of two elements. The first element is the net effect for all commuting blocks (including ancestors)
    *         except the first block (i.e. this effect will give the combined post state for all chosen commuting
    *         blocks when applied to the post-state of the first chosen block). The second element is the chosen
    *         list of blocks, which all commute with each other.
    *
    */
  def merge[F[_]: Monad: BlockStore](
      candidateParentBlocks: Seq[BlockMessage],
      dag: BlockDagRepresentation[F]
  ): F[(TransformMap, Vector[BlockMessage])] = {
    val candidateParents = candidateParentBlocks.map(BlockMetadata.fromBlock).toVector
    val n                = candidateParents.length

    def computeUncommonAncestors(
        implicit o: Ordering[BlockMetadata]
    ): F[Map[BlockMetadata, BitSet]] =
      DagOperations.uncommonAncestors[F](candidateParents, dag)

    def netEffect(blocks: Vector[BlockMetadata]): F[TransformMap] =
      blocks
        .traverse(block => BlockStore[F].getTransforms(block.blockHash))
        .map(_.flatten.foldLeft[TransformMap](Nil)(_ ++ _))

    if (n <= 1) {
      // no parents or single parent, nothing to merge
      candidateParents.traverse(b => ProtoUtil.unsafeGetBlock[F](b.blockHash)).map { blocks =>
        Seq.empty[TransformEntry] -> blocks
      }
    } else
      for {
        ordering          <- dag.deriveOrdering(0L) // TODO: Replace with an actual starting number
        uncommonAncestors <- computeUncommonAncestors(ordering)

        // collect uncommon ancestors based on which candidate they are an ancestor of
        groups = uncommonAncestors
          .foldLeft(Vector.fill(n)(Vector.empty[BlockMetadata]).zipWithIndex) {
            case (acc, (block, ancestry)) =>
              acc.map {
                case (group, index) =>
                  val newGroup = if (ancestry.contains(index)) group :+ block else group
                  newGroup -> index
              }
          } // sort in topological order to combine effects in the right order
          .map { case (group, _) => group.sorted(ordering) }

        // always choose the first parent
        initChosen       = Vector(0)
        initChosenEffect <- netEffect(groups(0)).map(Op.fromTransforms)
        // effects chosen apart from the first parent
        initNonFirstEffect = Seq.empty[TransformEntry]

        chosen <- (1 until n).toList
                   .foldM[F, (Vector[Int], OpMap[ipc.Key], TransformMap)](
                     (initChosen, initChosenEffect, initNonFirstEffect)
                   ) {
                     case (
                         unchanged @ (chosenSet, chosenEffect, chosenNonFirstEffect),
                         candidate
                         ) =>
                       val candidateEffectF = netEffect(
                         groups(candidate)
                           .filterNot { // remove ancestors already included in the chosenSet
                             block =>
                               val ancestry = uncommonAncestors(block)
                               chosenSet.exists(i => ancestry.contains(i))
                           }
                       )

                       // if candidate commutes with chosen set, then included, otherwise do not include it
                       candidateEffectF.map { candidateEffect =>
                         val ops = Op.fromTransforms(candidateEffect)
                         if (chosenEffect ~ ops)
                           (
                             chosenSet :+ candidate,
                             chosenEffect + ops,
                             chosenNonFirstEffect ++ candidateEffect
                           )
                         else
                           unchanged
                       }
                   }
        // The effect we return is the one which would be applied onto the first parent's
        // post-state, so we do not include the first parent in the effect.
        (chosenParents, _, nonFirstEffect) = chosen
        blocks <- chosenParents.traverse(
                   i => ProtoUtil.unsafeGetBlock[F](candidateParents(i).blockHash)
                 )
      } yield (nonFirstEffect, blocks)
  }
}
