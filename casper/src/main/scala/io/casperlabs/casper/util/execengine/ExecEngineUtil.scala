package io.casperlabs.casper.util.execengine

import cats.effect.Sync
import cats.implicits._
import cats.{Monad, MonadError}
import cats.kernel.{Monoid, Order}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper._
import io.casperlabs.casper.protocol.{BlockMessage, DeployData, ProcessedDeploy}
import io.casperlabs.casper.util.ProtoUtil.blockNumber
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, DagOperations, ProtoUtil}
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
    blockNumber: Long,
    protocolVersion: ProtocolVersion
)

object ExecEngineUtil {
  type StateHash = ByteString

  def computeDeploysCheckpoint[F[_]: MonadError[?[_], Throwable]: BlockStore: Log: ExecutionEngineService](
      parents: Seq[BlockMessage],
      deploys: Seq[DeployData],
      combinedEffect: TransformMap, // effect used to obtain combined post-state of all parents
      protocolVersion: ProtocolVersion
  ): F[DeploysCheckpoint] =
    for {
      processedHash <- processDeploys[F](
                        parents,
                        combinedEffect,
                        deploys,
                        protocolVersion
                      )
      (preStateHash, processedDeploys) = processedHash
      deployEffects                    = findCommutingEffects(processedDeployEffects(deploys zip processedDeploys))
      commutingEffects                 = deployEffects.map { case (_, eff, cost) => (eff, cost) }
      deploysForBlock = deployEffects.map {
        case (deploy, _, cost) => {
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
    } yield DeploysCheckpoint(preStateHash, postStateHash, deploysForBlock, number, protocolVersion)

  def processDeploys[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      parents: Seq[BlockMessage],
      combinedEffect: TransformMap, // effect used to obtain combined post-state of all parents
      deploys: Seq[DeployData],
      protocolVersion: ProtocolVersion
  ): F[(StateHash, Seq[DeployResult])] =
    for {
      prestate <- computePrestate[F](parents.toList, combinedEffect)
      ds       = deploys.map(ProtoUtil.deployDataToEEDeploy)
      result <- MonadError[F, Throwable].rethrow(
                 ExecutionEngineService[F].exec(prestate, ds, protocolVersion)
               )
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

  //TODO: How to handle errors?
  def findCommutingEffects(
      deployEffects: Seq[(DeployData, Option[(ExecutionEffect, Long)])]
  ): Seq[(DeployData, ExecutionEffect, Long)] = {
    val errorFree = deployEffects.collect {
      case (d, Some((eff, cost))) => (d, eff, cost)
    }.toList

    errorFree match {
      case Nil => Nil
      case (head @ (_, eff0, _)) :: tail =>
        val (result, _) = tail.foldLeft(Vector(head) -> Op.fromIpcEntry(eff0.opMap)) {
          case (unchanged @ (acc, totalOps), next @ (_, eff, _)) =>
            val ops = Op.fromIpcEntry(eff.opMap)
            if (totalOps ~ ops)
              (acc :+ next, totalOps + ops)
            else
              unchanged
        }

        result
    }
  }

  def effectsForBlock[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      block: BlockMessage,
      combinedEffect: TransformMap,
      dag: BlockDagRepresentation[F]
  ): F[(StateHash, Seq[TransformEntry])] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](block)
      deploys = ProtoUtil.deploys(block)
      protocolVersion = CasperLabsProtocolVersions.thresholdsVersionMap
        .fromBlockMessage(block)
      processedHash <- processDeploys[F](
                        parents,
                        combinedEffect,
                        deploys.flatMap(_.deploy),
                        protocolVersion
                      )
      (prestate, processedDeploys) = processedHash
      deployEffects                = processedDeployEffects(deploys.map(_.getDeploy) zip processedDeploys)
      transformMap = findCommutingEffects(deployEffects).flatMap {
        case (_, eff, _) => eff.transformMap
      }
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
  implicit val TransformMapMonoid: Monoid[TransformMap] = new Monoid[TransformMap] {
    def combine(t1: TransformMap, t2: TransformMap): TransformMap = t1 ++ t2
    def empty: TransformMap                                       = Nil
  }

  /** Computes the largest commuting sub-set of blocks from the `candidateParents` along with an effect which
    * can be used to find the combined post-state of those commuting blocks.
    * @param candidateParents blocks to attempt to merge
    * @return a tuple of two elements. The first element is the net effect for all commuting blocks (including ancestors)
    *         except the first block (i.e. this effect will give the combined post state for all chosen commuting
    *         blocks when applied to the post-state of the first chosen block). The second element is the chosen
    *         list of blocks, which all commute with each other.
    *
    */
  def abstractMerge[F[_]: Monad, T: Monoid, A: Order, K](
      candidates: IndexedSeq[A],
      parents: A => F[List[A]],
      effect: A => F[Option[T]],
      toOps: T => OpMap[K]
  ): F[(T, Vector[A])] = {
    val n = candidates.length

    def netEffect(blocks: Vector[A]): F[T] =
      blocks
        .traverse(block => effect(block))
        .map(_.flatten.foldLeft[T](Monoid[T].empty)(Monoid[T].combine))

    if (n <= 1) {
      (Monoid[T].empty -> candidates.toVector).pure[F]
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
          .map { case (group, _) => group.sorted(Order[A].toOrdering) }

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
      } yield (nonFirstEffect, blocks)
  }

  def merge[F[_]: Monad: BlockStore](
      candidateParentBlocks: Seq[BlockMessage],
      dag: BlockDagRepresentation[F]
  ): F[(TransformMap, Vector[BlockMessage])] = {

    def parents(b: BlockMetadata): F[List[BlockMetadata]] =
      b.parents.traverse(b => dag.lookup(b).map(_.get))

    def effect(block: BlockMetadata): F[Option[TransformMap]] =
      BlockStore[F].getTransforms(block.blockHash)

    def toOps(t: TransformMap): OpMap[ipc.Key] = Op.fromTransforms(t)

    val candidateParents = candidateParentBlocks.map(BlockMetadata.fromBlock).toVector

    for {
      ordering <- dag.deriveOrdering(0L) // TODO: Replace with an actual starting number
      merged <- {
        implicit val order = Order.fromOrdering(ordering)
        abstractMerge[F, TransformMap, BlockMetadata, ipc.Key](
          candidateParents,
          parents,
          effect,
          toOps
        )
      }
      (nonFirstEffect, chosenParents) = merged
      blocks                          <- chosenParents.traverse(block => ProtoUtil.unsafeGetBlock[F](block.blockHash))
    } yield (nonFirstEffect, blocks)
  }
}
