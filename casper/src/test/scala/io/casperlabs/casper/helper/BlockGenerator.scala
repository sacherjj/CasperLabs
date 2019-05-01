package io.casperlabs.casper.helper

import cats._
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore, IndexedBlockDagStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.DeploysCheckpoint
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{computeDeploysCheckpoint, StateHash}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.ipc.ProtocolVersion
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task

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
                 dag,
                 ProtocolVersion(1)
               )
    } yield result

}

trait BlockGenerator {
  def createBlock[F[_]: Monad: Time: BlockStore: IndexedBlockDagStorage](
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      postStateHash: ByteString = ByteString.EMPTY,
      shardId: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY
  ): F[BlockMessage] =
    for {
      now <- Time[F].currentMillis
      postState = RChainState()
        .withPreStateHash(preStateHash)
        .withPostStateHash(postStateHash)
        .withBonds(bonds)
      computedHash = Blake2b256.hash(postState.toByteArray)
      header = Header()
        .withPostStateHash(ByteString.copyFrom(computedHash))
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
