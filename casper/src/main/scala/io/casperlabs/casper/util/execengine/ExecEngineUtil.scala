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
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, DagOperations, ProtoUtil}
import io.casperlabs.ipc._
import io.casperlabs.models.{DeployResult => _, _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

case class DeploysCheckpoint(
    preStateHash: StateHash,
    postStateHash: StateHash,
    deploysForBlock: Seq[ProcessedDeploy],
    blockNumber: Long,
    protocolVersion: ProtocolVersion
)

object ExecEngineUtil {
  type StateHash = ByteString

  implicit def functorRaiseInvalidBlock[F[_]: Sync] = Validate.raiseValidateErrorThroughSync[F]

  def validateBlockCheckpoint[F[_]: Sync: Log: BlockStore: ExecutionEngineService](
      b: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Either[Throwable, StateHash]] =
    (for {
      processedHash           <- ExecEngineUtil.effectsForBlock(b, dag)
      (preStateHash, effects) = processedHash
      _                       <- Validate.transactions[F](b, dag, preStateHash, effects)
    } yield ProtoUtil.postStateHash(b)).attempt

  def computeDeploysCheckpoint[F[_]: MonadError[?[_], Throwable]: BlockStore: Log: ExecutionEngineService](
      parents: Seq[BlockMessage],
      deploys: Seq[DeployData],
      dag: BlockDagRepresentation[F],
      protocolVersion: ProtocolVersion
  ): F[DeploysCheckpoint] =
    for {
      processedHash <- processDeploys(
                        parents,
                        dag,
                        deploys,
                        protocolVersion
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
    } yield DeploysCheckpoint(preStateHash, postStateHash, deploysForBlock, number, protocolVersion)

  def processDeploys[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      parents: Seq[BlockMessage],
      dag: BlockDagRepresentation[F],
      deploys: Seq[DeployData],
      protocolVersion: ProtocolVersion
  ): F[(StateHash, Seq[DeployResult])] =
    for {
      prestate <- computePrestate[F](parents.toList, dag)
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
      dag: BlockDagRepresentation[F]
  ): F[(StateHash, Seq[TransformEntry])] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](block)
      deploys = ProtoUtil.deploys(block)
      protocolVersion = CasperLabsProtocolVersions.thresholdsVersionMap
        .fromBlockMessage(block)
      processedHash <- processDeploys(
                        parents,
                        dag,
                        deploys.flatMap(_.deploy),
                        protocolVersion
                      )
      (prestate, processedDeploys) = processedHash
      deployEffects                = processedDeployEffects(deploys.map(_.getDeploy) zip processedDeploys)
      transformMap                 = findCommutingEffects(deployEffects).unzip._1.flatMap(_.transformMap)
    } yield (prestate, transformMap)

  private def computePrestate[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      parents: List[BlockMessage],
      dag: BlockDagRepresentation[F]
  ): F[StateHash] = parents match {
    case Nil => ExecutionEngineService[F].emptyStateHash.pure[F] //no parents
    case soleParent :: Nil =>
      ProtoUtil.postStateHash(soleParent).pure[F] //single parent
    case initParent :: _ => //multiple parents
      for {
        bs <- blocksToApply[F](parents, dag)
        diffs <- bs
                  .traverse(
                    b =>
                      BlockStore[F]
                        .getTransforms(b.blockHash)
                        .map(_.getOrElse(Seq.empty[TransformEntry]))
                  )
                  .map(_.flatten)
        prestate = ProtoUtil.postStateHash(initParent)
        result <- MonadError[F, Throwable].rethrow(
                   ExecutionEngineService[F].commit(prestate, diffs)
                 )
      } yield result
  }

  private[execengine] def blocksToApply[F[_]: Monad](
      parents: Seq[BlockMessage],
      dag: BlockDagRepresentation[F]
  ): F[Vector[BlockMetadata]] =
    for {
      parentsMetadata <- parents.toList.traverse(b => dag.lookup(b.blockHash).map(_.get))
      ordering        <- dag.deriveOrdering(0L) // TODO: Replace with an actual starting number
      blockHashesToApply <- {
        implicit val o: Ordering[BlockMetadata] = ordering
        for {
          uncommonAncestors          <- DagOperations.uncommonAncestors[F](parentsMetadata.toVector, dag)
          ancestorsOfInitParentIndex = 0
          // Filter out blocks that already included by starting from the chosen initial parent
          // as otherwise we will be applying the initial parent's ancestor's twice.
          result = uncommonAncestors
            .filterNot { case (_, set) => set.contains(ancestorsOfInitParentIndex) }
            .keys
            .toVector
            .sorted // Ensure blocks to apply is topologically sorted to maintain any causal dependencies
        } yield result
      }
    } yield blockHashesToApply
}
