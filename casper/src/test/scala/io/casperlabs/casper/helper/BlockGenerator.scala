package io.casperlabs.casper.helper

import cats._
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore, IndexedBlockDagStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.casper.util.rholang.{InterpreterUtil, ProcessedDeployUtil, RuntimeManager}
import io.casperlabs.catscontrib._
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.collection.immutable.HashMap
import scala.language.higherKinds

object BlockGenerator {
  implicit val timeEff = new LogicalTime[Task]()

  def updateChainWithBlockStateUpdate[F[_]: Sync: BlockStore: IndexedBlockDagStorage: ExecutionEngineService: ToAbstractContext](
      id: Int,
      genesis: BlockMessage,
      runtimeManager: RuntimeManager[F]
  ): F[BlockMessage] =
    for {
      b   <- IndexedBlockDagStorage[F].lookupByIdUnsafe(id)
      dag <- IndexedBlockDagStorage[F].getRepresentation
      computeBlockCheckpointResult <- computeBlockCheckpoint[F](
                                       b,
                                       genesis,
                                       dag,
                                       runtimeManager
                                     )
      (postStateHash, processedDeploys) = computeBlockCheckpointResult
      _                                 <- injectPostStateHash[F](id, b, postStateHash, processedDeploys)
    } yield b

  def computeBlockCheckpoint[F[_]: Sync: BlockStore: ExecutionEngineService: ToAbstractContext](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F],
      runtimeManager: RuntimeManager[F]
  ): F[(StateHash, Seq[ProcessedDeploy])] =
    for {
      result <- InterpreterUtil
                 .computeBlockCheckpointFromDeploys[F](b, genesis, dag, runtimeManager)
      Right((preStateHash, postStateHash, processedDeploys)) = result
    } yield (postStateHash, processedDeploys.map(ProcessedDeployUtil.fromInternal))

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
    BlockStore[F].put(b.blockHash, updatedBlock) *>
      IndexedBlockDagStorage[F].inject(id, updatedBlock)
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
      _             <- BlockStore[F].put(serializedBlockHash, modifiedBlock)
    } yield modifiedBlock
}
