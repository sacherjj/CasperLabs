package io.casperlabs.casper.util.rholang

import cats.Monad
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockMetadata, BlockStore}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.{BlockException, PrettyPrinter}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc.ExecutionEffect
import io.casperlabs.models._
import io.casperlabs.shared.{Log, LogSource}
import io.casperlabs.smartcontracts.ExecutionEngineService

object InterpreterUtil {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  //Returns (None, checkpoints) if the block's tuplespace hash
  //does not match the computed hash based on the deploys
  def validateBlockCheckpoint[F[_]: Sync: Log: BlockStore](
      b: BlockMessage,
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F]
  ): F[Either[BlockException, Option[StateHash]]] = {
    val preStateHash    = ProtoUtil.preStateHash(b)
    val tsHash          = ProtoUtil.tuplespace(b)
    val deploys         = ProtoUtil.deploys(b)
    val internalDeploys = deploys.flatMap(ProcessedDeployUtil.toInternal)
    val timestamp       = Some(b.header.get.timestamp) // TODO: Ensure header exists through type
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)
      possiblePreStateHash <- computeParentsPostState[F](
                               parents,
                               dag,
                               runtimeManager
                             )
      _ <- Log[F].info(s"Computed parents post state for ${PrettyPrinter.buildString(b)}.")
      result <- processPossiblePreStateHash[F](
                 runtimeManager,
                 preStateHash,
                 tsHash,
                 internalDeploys,
                 possiblePreStateHash,
                 timestamp
               )
    } yield result
  }

  private def processPossiblePreStateHash[F[_]: Sync: Log: BlockStore](
      runtimeManager: RuntimeManager[F],
      preStateHash: StateHash,
      tsHash: Option[StateHash],
      internalDeploys: Seq[InternalProcessedDeploy],
      possiblePreStateHash: Either[Throwable, StateHash],
      time: Option[Long]
  ): F[Either[BlockException, Option[StateHash]]] =
    possiblePreStateHash match {
      case Left(ex) =>
        Left(BlockException(ex)).rightCast[Option[StateHash]].pure[F]
      case Right(computedPreStateHash) =>
        if (preStateHash == computedPreStateHash) {
          processPreStateHash[F](
            runtimeManager,
            preStateHash,
            tsHash,
            internalDeploys,
            possiblePreStateHash,
            time
          )
        } else {
          Log[F].warn(
            s"Computed pre-state hash ${PrettyPrinter.buildString(computedPreStateHash)} does not equal block's pre-state hash ${PrettyPrinter
              .buildString(preStateHash)}"
          ) *> Right(none[StateHash]).leftCast[BlockException].pure[F]
        }
    }

  private def processPreStateHash[F[_]: Monad: Log: BlockStore](
      runtimeManager: RuntimeManager[F],
      preStateHash: StateHash,
      tsHash: Option[StateHash],
      internalDeploys: Seq[InternalProcessedDeploy],
      possiblePreStateHash: Either[Throwable, StateHash],
      time: Option[Long]
  ): F[Either[BlockException, Option[StateHash]]] =
    runtimeManager
      .replayComputeState(preStateHash, internalDeploys, time)
      .flatMap {
        case Left((Some(deploy), status)) =>
          status match {
            case InternalErrors(exs) =>
              Left(
                BlockException(
                  new Exception(s"Internal errors encountered while processing ${PrettyPrinter
                    .buildString(deploy)}: ${exs.mkString("\n")}")
                )
              ).rightCast[Option[StateHash]].pure[F]
            case UserErrors(errors: Vector[Throwable]) =>
              Log[F].warn(s"Found user error(s) ${errors.map(_.getMessage).mkString("\n")}") *> Right(
                none[StateHash]
              ).leftCast[BlockException].pure[F]
            case UnknownFailure =>
              Log[F].warn(s"Found unknown failure") *> Right(none[StateHash])
                .leftCast[BlockException]
                .pure[F]
          }
        case Left((None, _)) =>
          //TODO Log error
          ???
        case Right(computedStateHash) =>
          if (tsHash.contains(computedStateHash)) {
            // state hash in block matches computed hash!
            Right(Option(computedStateHash))
              .leftCast[BlockException]
              .pure[F]
          } else {
            // state hash in block does not match computed hash -- invalid!
            // return no state hash, do not update the state hash set
            Log[F].warn(
              s"Tuplespace hash ${tsHash.getOrElse(ByteString.EMPTY)} does not match computed hash $computedStateHash."
            ) *> Right(none[StateHash]).leftCast[BlockException].pure[F]
          }
      }

  def computeDeploysCheckpoint[F[_]: Sync: BlockStore](
      parents: Seq[BlockMessage],
      deploysWithEffect: Seq[(Deploy, ExecutionEffect)],
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F],
      time: Option[Long] = None
  ): F[Either[Throwable, (StateHash, StateHash, Seq[InternalProcessedDeploy])]] =
    for {
      possiblePreStateHash <- computeParentsPostState[F](parents, dag, runtimeManager)
      res <- possiblePreStateHash match {
              case Right(preStateHash) =>
                runtimeManager
                  .computeState(preStateHash, deploysWithEffect, time)
                  .map {
                    case (postStateHash, processedDeploy) =>
                      Right(preStateHash, postStateHash, processedDeploy)
                  }
              case Left(err) =>
                Left(err).pure[F]
            }
    } yield res

  private def computeParentsPostState[F[_]: Sync: BlockStore](
      parents: Seq[BlockMessage],
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F]
  ): F[Either[Throwable, StateHash]] = {
    val parentTuplespaces = parents.flatMap(p => ProtoUtil.tuplespace(p).map(p -> _))

    parentTuplespaces match {
      // For genesis, use empty trie's root hash
      case Seq() =>
        Right(runtimeManager.emptyStateHash).leftCast[Throwable].pure[F]

      case Seq((_, parentStateHash)) =>
        Right(parentStateHash).leftCast[Throwable].pure[F]

      case (_, initStateHash) +: _ =>
        computeMultiParentsPostState[F](
          parents,
          dag,
          runtimeManager,
          initStateHash
        )
    }
  }
  // In the case of multiple parents we need to apply all of the deploys that have been
  // made in all of the branches of the DAG being merged. This is done by computing uncommon ancestors
  // and applying the deploys in those blocks on top of the initial parent.
  private def computeMultiParentsPostState[F[_]: Sync: BlockStore](
      parents: Seq[BlockMessage],
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F],
      initStateHash: StateHash
  ): F[Either[Throwable, StateHash]] =
    for {
      blockHashesToApply <- findMultiParentsBlockHashesForReplay(parents, dag)
      blocksToApply      <- blockHashesToApply.traverse(b => ProtoUtil.unsafeGetBlock[F](b.blockHash))
      replayResult <- blocksToApply.toList.foldM(Right(initStateHash).leftCast[Throwable]) {
                       (acc, block) =>
                         acc match {
                           case Right(stateHash) =>
                             val deploys =
                               block.getBody.deploys.flatMap(ProcessedDeployUtil.toInternal)
                             val time = Some(block.header.get.timestamp)
                             for {
                               replayResult <- runtimeManager.replayComputeState(
                                                stateHash,
                                                deploys,
                                                time
                                              )
                             } yield
                               replayResult match {
                                 case result @ Right(_) => result.leftCast[Throwable]
                                 case Left((_, status)) =>
                                   val parentHashes =
                                     parents.map(
                                       p => Base16.encode(p.blockHash.toByteArray).take(8)
                                     )
                                   Left(
                                     new Exception(
                                       s"Failed status while computing post state of $parentHashes: $status"
                                     )
                                   )
                               }
                           case Left(_) => acc.pure[F]
                         }
                     }
    } yield replayResult

  private[rholang] def findMultiParentsBlockHashesForReplay[F[_]: Monad](
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

  private[casper] def computeBlockCheckpointFromDeploys[F[_]: Sync: BlockStore: ExecutionEngineService](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F]
  ): F[Either[Throwable, (StateHash, StateHash, Seq[InternalProcessedDeploy])]] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)

      deploys: Seq[Deploy] = ProtoUtil.deploys(b).flatMap(_.deploy)
      deploysEffect <- deploys.toList
                        .foldM[F, Either[Throwable, Seq[(Deploy, ExecutionEffect)]]](
                          Seq()
                            .asRight[Throwable]
                        ) {
                          case (Left(e), _) =>
                            e.asLeft[Seq[(Deploy, ExecutionEffect)]]
                              .pure[F]
                          case (Right(acc), d) =>
                            d.raw match {
                              case Some(r) =>
                                runtimeManager
                                  .sendDeploy(
                                    ProtoUtil
                                      .deployDataToEEDeploy(r)
                                  )
                                  .map {
                                    case Left(e) =>
                                      e.asLeft[Seq[(Deploy, ExecutionEffect)]]
                                    case Right(effect) =>
                                      (acc :+ (d, effect))
                                        .asRight[Throwable]
                                  }
                              case None =>
                                (acc :+ (d, ExecutionEffect()))
                                  .asRight[Throwable]
                                  .pure[F]
                            }
                        }
      _ = assert(
        parents.nonEmpty || (parents.isEmpty && b == genesis),
        "Received a different genesis block."
      )
      result <- deploysEffect match {
                 case Left(e) =>
                   e.asLeft[(StateHash, StateHash, Seq[InternalProcessedDeploy])].pure[F]
                 case Right(d) =>
                   computeDeploysCheckpoint[F](
                     parents,
                     d,
                     dag,
                     runtimeManager
                   )
               }
    } yield result
}
