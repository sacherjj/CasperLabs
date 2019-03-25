package io.casperlabs.casper.util.execengine

import cats.implicits._
import cats.{Monad, MonadError}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.protocol.{BlockMessage, DeployData, ProcessedDeploy}
import io.casperlabs.casper.util.ProtoUtil.blockNumber
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.{protocol, PrettyPrinter}
import io.casperlabs.ipc
import io.casperlabs.ipc._
import io.casperlabs.models.{DeployResult => _, _}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService

case class DeploysCheckpoint(
    preStateHash: StateHash,
    postStateHash: StateHash,
    deploysForBlock: Seq[ProcessedDeploy],
    blockNumber: Long
)

object ExecEngineUtil {
  type StateHash = ByteString

  def deploy2deploy(d: DeployData): Deploy =
    d match {
      case DeployData(
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

  def computeDeploysCheckpoint[F[_]: MonadError[?[_], Throwable]: BlockStore: Log: ExecutionEngineService](
      parents: Seq[BlockMessage],
      deploys: Seq[DeployData],
      dag: BlockDagRepresentation[F]
  ): F[DeploysCheckpoint] =
    for {
      processedHash <- ExecEngineUtil.processDeploys(
                        parents,
                        dag,
                        deploys
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
      dag: BlockDagRepresentation[F],
      deploys: Seq[DeployData]
  ): F[(StateHash, Seq[DeployResult])] =
    for {
      prestate <- computePrestate[F](parents.toList, dag)
      ds       = deploys.map(deploy2deploy)
      result   <- MonadError[F, Throwable].rethrow(ExecutionEngineService[F].exec(prestate, ds))
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

  def effectsForBlock[F[_]: MonadError[?[_], Throwable]: BlockStore: ExecutionEngineService](
      block: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[(StateHash, Seq[TransformEntry])] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](block)
      deploys = ProtoUtil.deploys(block)
      processedHash <- processDeploys(
                        parents,
                        dag,
                        deploys.flatMap(_.deploy)
                      )
      (prestate, processedDeploys) = processedHash
      transformMap                 = findCommutingEffects(processedDeploys).unzip._1.flatMap(_.transformMap)
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
                        .get(b.blockHash)
                        .map(_.fold(Seq.empty[TransformEntry])(_.transformEntry))
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
