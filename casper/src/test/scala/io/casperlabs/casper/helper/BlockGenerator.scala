package io.casperlabs.casper.helper

import cats._
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore, IndexedBlockDagStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.{protocol, BlockException, PrettyPrinter}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.{DeploysCheckpoint, ExecEngineUtil}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{
  computeDeploysCheckpoint,
  findCommutingEffects,
  processDeploys,
  StateHash
}
import io.casperlabs.catscontrib._
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.ipc.{DeployResult, TransformEntry}
import io.casperlabs.models.BlockMetadata
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.HashMap
import scala.language.higherKinds

object BlockGenerator {
  implicit val timeEff = new LogicalTime[Task]()

  def updateChainWithBlockStateUpdate[F[_]: Sync: BlockStore: IndexedBlockDagStorage: ExecutionEngineService: Log](
      id: Int,
      genesis: BlockMessage
  ): F[BlockMessage] =
    for {
      b   <- IndexedBlockDagStorage[F].lookupByIdUnsafe(id)
      dag <- IndexedBlockDagStorage[F].getRepresentation
      computeBlockCheckpointResult <- computeBlockCheckpoint[F](
                                       b,
                                       genesis,
                                       dag
                                     )
      (postStateHash, processedDeploys) = computeBlockCheckpointResult
      _                                 <- injectPostStateHash[F](id, b, postStateHash, processedDeploys)
    } yield b

  def computeBlockCheckpoint[F[_]: Sync: BlockStore: ExecutionEngineService: Log](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[(StateHash, Seq[ProcessedDeploy])] =
    for {
      result <- computeBlockCheckpointFromDeploys[F](
                 b,
                 genesis,
                 dag
               )
    } yield (result.postStateHash, result.deploysForBlock)

  def injectPostStateHash[F[_]: Monad: BlockStore: IndexedBlockDagStorage](
      id: Int,
      b: BlockMessage,
      postGenStateHash: StateHash,
      processedDeploys: Seq[ProcessedDeploy]
  ): F[Unit] = {
    val updatedBlockPostState = b.getBody.getState.withPostStateHash(postGenStateHash)
    val updatedBlockBody =
      b.getBody.withState(updatedBlockPostState).withDeploys(processedDeploys)
    val updatedBlock = b.withBody(updatedBlockBody)
    BlockStore[F].put(b.blockHash, updatedBlock, Seq.empty) *>
      IndexedBlockDagStorage[F].inject(id, updatedBlock)
  }

  private[casper] def computeBlockCheckpointFromDeploys[F[_]: Sync: BlockStore: Log: ExecutionEngineService](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[DeploysCheckpoint] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)

      deploys = ProtoUtil.deploys(b).flatMap(_.deploy)

      _ = assert(
        parents.nonEmpty || (parents.isEmpty && b == genesis),
        "Received a different genesis block."
      )

      result <- computeDeploysCheckpoint[F](
                 parents,
                 deploys,
                 dag
               )
    } yield result

  //Returns (None, checkpoints) if the block's tuplespace hash
  //does not match the computed hash based on the deploys
  def validateBlockCheckpoint[F[_]: Sync: Log: BlockStore: ExecutionEngineService](
      b: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Either[BlockException, Option[StateHash]]] = {
    val preStateHash = ProtoUtil.preStateHash(b)
    val tsHash       = ProtoUtil.tuplespace(b)
    val deploys      = ProtoUtil.deploys(b).flatMap(_.deploy)
    val timestamp    = Some(b.header.get.timestamp) // TODO: Ensure header exists through type
    for {
      parents                              <- ProtoUtil.unsafeGetParents[F](b)
      processedHash                        <- processDeploys(parents, dag, deploys)
      (computePreStateHash, deployResults) = processedHash
      _                                    <- Log[F].info(s"Computed parents post state for ${PrettyPrinter.buildString(b)}.")
      result <- processPossiblePreStateHash[F](
                 preStateHash,
                 tsHash,
                 deployResults,
                 computePreStateHash,
                 timestamp,
                 deploys
               )
    } yield result
  }

  private def processPossiblePreStateHash[F[_]: Sync: Log: BlockStore: ExecutionEngineService](
      preStateHash: StateHash,
      tsHash: Option[StateHash],
      deployResults: Seq[DeployResult],
      computedPreStateHash: StateHash,
      time: Option[Long],
      deploys: Seq[DeployData]
  ): F[Either[BlockException, Option[StateHash]]] =
    if (preStateHash == computedPreStateHash) {
      processPreStateHash[F](
        preStateHash,
        tsHash,
        deployResults,
        computedPreStateHash,
        time,
        deploys
      )
    } else {
      Log[F].warn(
        s"Computed pre-state hash ${PrettyPrinter.buildString(computedPreStateHash)} does not equal block's pre-state hash ${PrettyPrinter
          .buildString(preStateHash)}"
      ) *> Right(none[StateHash]).leftCast[BlockException].pure[F]
    }

  private def processPreStateHash[F[_]: Sync: Log: BlockStore: ExecutionEngineService](
      preStateHash: StateHash,
      tsHash: Option[StateHash],
      processedDeploys: Seq[DeployResult],
      possiblePreStateHash: StateHash,
      time: Option[Long],
      deploys: Seq[DeployData]
  ): F[Either[BlockException, Option[StateHash]]] = {
    val deployLookup     = processedDeploys.zip(deploys).toMap
    val commutingEffects = findCommutingEffects(processedDeploys)
    val transforms       = commutingEffects.unzip._1.flatMap(_.transformMap)
    ExecutionEngineService[F].commit(preStateHash, transforms).flatMap {
      case Left(ex) =>
        Log[F].warn(s"Found unknown failure") *> Right(none[StateHash])
          .leftCast[BlockException]
          .pure[F]
      case Right(computedStateHash) =>
        if (tsHash.contains(computedStateHash)) {
          //state hash in block matches computed hash!
          Right(Option(computedStateHash))
            .leftCast[BlockException]
            .pure[F]
        } else {
          // state hash in block does not match computed hash -- invalid!
          // return no state hash, do not update the state hash set
          Log[F].warn(
            s"Tuplespace hash ${PrettyPrinter.buildString(tsHash.getOrElse(ByteString.EMPTY))} does not match computed hash ${PrettyPrinter
              .buildString(computedStateHash)}."
          ) *> Right(none[StateHash]).leftCast[BlockException].pure[F]
        }
    }
  }
}

trait BlockGenerator {
  def createBlock[F[_]: Monad: Time: BlockStore: IndexedBlockDagStorage](
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      tsHash: ByteString = ByteString.EMPTY,
      shardId: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY
  ): F[BlockMessage] =
    for {
      now <- Time[F].currentMillis
      postState = RChainState()
        .withPreStateHash(preStateHash)
        .withPostStateHash(tsHash)
        .withBonds(bonds)
      postStateHash = Blake2b256.hash(postState.toByteArray)
      header = Header()
        .withPostStateHash(ByteString.copyFrom(postStateHash))
        .withParentsHashList(parentsHashList)
        .withDeploysHash(ProtoUtil.protoSeqHash(deploys))
        .withTimestamp(now)
      blockHash = Blake2b256.hash(header.toByteArray)
      body      = Body().withState(postState).withDeploys(deploys)
      serializedJustifications = justifications.toList.map {
        case (creator: Validator, latestBlockHash: BlockHash) =>
          Justification(creator, latestBlockHash)
      }
      serializedBlockHash = ByteString.copyFrom(blockHash)
      block = BlockMessage(
        serializedBlockHash,
        Some(header),
        Some(body),
        serializedJustifications,
        creator,
        shardId = shardId
      )
      modifiedBlock <- IndexedBlockDagStorage[F].insertIndexed(block)
      _ <- BlockStore[F]
            .put(serializedBlockHash, modifiedBlock, Seq.empty)
    } yield modifiedBlock
}
