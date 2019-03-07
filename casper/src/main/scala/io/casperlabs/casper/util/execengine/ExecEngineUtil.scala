package io.casperlabs.casper.util.execengine

import cats.Monad
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.{protocol, BlockException, PrettyPrinter}
import io.casperlabs.casper.protocol.{BlockMessage, Bond, ProcessedDeploy}
import io.casperlabs.casper.util.ProtoUtil.blockNumber
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import io.casperlabs.ipc._
import io.casperlabs.models.{DeployResult => _, _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

object ExecEngineUtil {
  type StateHash = ByteString

  def deploy2deploy(d: protocol.Deploy): Deploy =
    d.raw.fold(Deploy()) {
      case protocol.DeployData(
          addr,
          time,
          sCode,
          pCode,
          gasLimit,
          gasPrice,
          nonce,
          _,
          _,
          _
          ) =>
        Deploy(
          addr,
          time,
          sCode,
          pCode,
          gasLimit,
          gasPrice,
          nonce
        )
    }

  def computeDeploysCheckpoint[F[_]: Sync: Log: ExecutionEngineService](
      parents: Seq[BlockMessage],
      deploys: Seq[protocol.Deploy],
      dag: BlockDagRepresentation[F],
      //TODO: this parameter should not be needed because the BlockDagRepresentation could hold this info
      transforms: BlockMetadata => F[Seq[TransformEntry]]
  ): F[(StateHash, StateHash, Seq[ProcessedDeploy], Long)] =
    for {
      processedHash <- ExecEngineUtil.processDeploys(
                        parents,
                        dag,
                        deploys,
                        transforms
                      )
      (preStateHash, processedDeploys) = processedHash
      deployLookup                     = processedDeploys.zip(deploys).toMap
      commutingEffects                 = ExecEngineUtil.findCommutingEffects(processedDeploys)
      deploysForBlock = commutingEffects.map {
        case (eff, cost) => {
          val deploy = deployLookup(
            ipc.DeployResult(
              cost,
              ipc.DeployResult.Result.Effects(eff)
            )
          )
          protocol.ProcessedDeploy(
            Some(deploy),
            cost,
            false
          )
        }
      }
      transforms            = commutingEffects.unzip._1.flatMap(_.transformMap)
      possiblePostStateHash <- ExecutionEngineService[F].commit(preStateHash, transforms)
      postStateHash <- possiblePostStateHash match {
                        case Left(ex)    => Sync[F].raiseError(ex)
                        case Right(hash) => hash.pure[F]
                      }
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
    } yield (preStateHash, postStateHash, deploysForBlock, number)

  def processDeploys[F[_]: Sync: ExecutionEngineService](
      parents: Seq[BlockMessage],
      dag: BlockDagRepresentation[F],
      deploys: Seq[protocol.Deploy],
      //TODO: this parameter should not be needed because the BlockDagRepresentation could hold this info
      transforms: BlockMetadata => F[Seq[TransformEntry]]
  ): F[(StateHash, Seq[DeployResult])] =
    for {
      prestate       <- computePrestate[F](parents.toList, dag, transforms)
      ds             = deploys.map(deploy2deploy)
      possibleResult <- ExecutionEngineService[F].exec(prestate, ds)
      result <- possibleResult match {
                 case Left(ex)             => Sync[F].raiseError(ex)
                 case Right(deployResults) => deployResults.pure[F]
               }
    } yield (prestate, result)

  //TODO: actually find which ones commute
  //TODO: How to handle errors?
  def findCommutingEffects(processedDeploys: Seq[DeployResult]): Seq[(ExecutionEffect, Long)] =
    processedDeploys.flatMap {
      case DeployResult(_, DeployResult.Result.Empty) =>
        None //This should never happen either
      case DeployResult(errCost, DeployResult.Result.Error(_)) =>
        None //We should not be ignoring error cost
      case DeployResult(cost, DeployResult.Result.Effects(eff)) =>
        Some((eff, cost))
    }

  def effectsForBlock[F[_]: Sync: BlockStore: ExecutionEngineService](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      transforms: BlockMetadata => F[Seq[TransformEntry]]
  ): F[(StateHash, Seq[TransformEntry])] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](block)
      deploys = ProtoUtil.deploys(block)
      processedHash <- processDeploys(
                        parents,
                        dag,
                        deploys.flatMap(_.deploy),
                        transforms
                      )
      (prestate, processedDeploys) = processedHash
      transformMap                 = findCommutingEffects(processedDeploys).unzip._1.flatMap(_.transformMap)
    } yield (prestate, transformMap)

  private def computePrestate[F[_]: Sync: ExecutionEngineService](
      parents: List[BlockMessage],
      dag: BlockDagRepresentation[F],
      transforms: BlockMetadata => F[Seq[TransformEntry]]
  ): F[StateHash] = parents match {
    case Nil => ExecutionEngineService[F].emptyStateHash.pure[F] //no parents
    case soleParent :: Nil =>
      ProtoUtil.postStateHash(soleParent).pure[F] //single parent
    case initParent :: _ => //multiple parents
      for {
        bs             <- blocksToApply[F](parents, dag)
        diffs          <- bs.traverse(transforms).map(_.flatten)
        prestate       = ProtoUtil.postStateHash(initParent)
        possibleResult <- ExecutionEngineService[F].commit(prestate, diffs)
        result <- possibleResult match {
                   case Left(ex)    => Sync[F].raiseError(ex)
                   case Right(hash) => hash.pure[F]
                 }
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
