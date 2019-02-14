package io.casperlabs.casper.util.execengine

import cats.Monad
import cats.implicits._

import com.google.protobuf.ByteString

import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockMetadata, BlockStore}
import io.casperlabs.casper.protocol
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.ipc._
import io.casperlabs.shared.{Log, LogSource}
import io.casperlabs.smartcontracts.ExecutionEngineService

import scala.util.Try

object ExecEngineUtil {
  private implicit val logSource: LogSource = LogSource(this.getClass)
  type StateHash = ByteString

  //TODO: This function should not be needed; ipc.deploy and protocol.deploy are
  //redundant and we should not have both.
  private def deploy2deploy(d: protocol.Deploy): Deploy =
    d.raw.fold(Deploy()) {
      case protocol.DeployData(
          addr,
          time,
          sCode,
          pCode,
          gasLimit,
          gasPrice,
          nonce,
          sigAlgorithm,
          sig,
          _
          ) =>
        Deploy(
          addr,
          time,
          sCode,
          pCode,
          gasLimit,
          gasPrice,
          nonce,
          sigAlgorithm,
          sig
        )
    }

  def processDeploys[F[_]: Monad](
      parents: Seq[BlockMessage],
      dag: BlockDagRepresentation[F],
      ee: ExecutionEngineService[F],
      deploys: Seq[protocol.Deploy],
      //TODO: this parameter should not be needed because the BlockDagRepresentation could hold this info
      transforms: BlockMetadata => F[Seq[TransformEntry]]
  ): F[Either[Throwable, (StateHash, Seq[DeployResult])]] =
    for {
      prestate <- computePrestate[F](parents.toList, dag, ee, transforms)
      ds       = deploys.map(deploy2deploy)
      result   <- prestate.traverse(ps => ee.exec(ps, ds).map(_.map(ps -> _)))
    } yield result.joinRight

  //TODO: actually find which ones commute
  //TODO: How to handle errors?
  def commutingEffects(processedDeploys: Seq[DeployResult]): Seq[ExecutionEffect] =
    processedDeploys.flatMap {
      case DeployResult(DeployResult.Result.Empty)        => None
      case DeployResult(DeployResult.Result.Error(_))     => None
      case DeployResult(DeployResult.Result.Effects(eff)) => Some(eff)
    }

  def effectsForBlock[F[_]: Monad: BlockStore](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      ee: ExecutionEngineService[F],
      transforms: BlockMetadata => F[Seq[TransformEntry]]
  ): F[Either[Throwable, Seq[TransformEntry]]] =
    for {
      parents        <- ProtoUtil.unsafeGetParents[F](block)
      deploys        = ProtoUtil.deploys(block)
      blockPreState  = ProtoUtil.preStateHash(block)
      blockPostState = ProtoUtil.postStateHash(block)
      possiblePreStateHash <- ExecEngineUtil.processDeploys(
                               parents,
                               dag,
                               ee,
                               deploys.flatMap(_.deploy),
                               transforms
                             )

    } yield
      possiblePreStateHash match {
        case Left(ex) => Left(ex)
        case Right((_, processedDeploys)) =>
          Right(commutingEffects(processedDeploys).flatMap(_.transformMap))
      }

  private def computePrestate[F[_]: Monad](
      parents: List[BlockMessage],
      dag: BlockDagRepresentation[F],
      ee: ExecutionEngineService[F],
      transforms: BlockMetadata => F[Seq[TransformEntry]]
  ): F[Either[Throwable, StateHash]] = parents match {
    case Nil => ee.emptyStateHash.asRight[Throwable].pure[F] //no parents
    case soleParent :: Nil =>
      Try(ProtoUtil.tuplespace(soleParent).get).toEither.pure[F] //single parent
    case initParent :: _ => //multiple parents
      for {
        bs       <- blocksToApply[F](parents, dag)
        diffs    <- bs.traverse(transforms).map(_.flatten)
        prestate = Try(ProtoUtil.tuplespace(initParent).get).toEither
        result   <- prestate.traverse(ee.commit(_, diffs))
      } yield result.joinRight
  }

  private def blocksToApply[F[_]: Monad](
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
