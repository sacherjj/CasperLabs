package io.casperlabs.casper.helper

import cats._
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStorage, DagRepresentation, IndexedDagStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus._, Block.ProcessedDeploy
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.DeploysCheckpoint
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{computeDeploysCheckpoint, StateHash}
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task

import scala.collection.immutable.HashMap
import scala.language.higherKinds

object BlockGenerator {
  implicit val timeEff = new LogicalTime[Task]()

  def updateChainWithBlockStateUpdate[F[_]: Sync: BlockStorage: IndexedDagStorage: ExecutionEngineService: Log](
      id: Int,
      genesis: Block
  ): F[Block] =
    for {
      b   <- IndexedDagStorage[F].lookupByIdUnsafe(id)
      dag <- IndexedDagStorage[F].getRepresentation
      computeBlockCheckpointResult <- computeBlockCheckpoint[F](
                                       b,
                                       genesis,
                                       dag
                                     )
      (postStateHash, processedDeploys) = computeBlockCheckpointResult
      _                                 <- injectPostStateHash[F](id, b, postStateHash, processedDeploys)
    } yield b

  def computeBlockCheckpoint[F[_]: Sync: BlockStorage: ExecutionEngineService: Log](
      b: Block,
      genesis: Block,
      dag: DagRepresentation[F]
  ): F[(StateHash, Seq[ProcessedDeploy])] =
    for {
      result <- computeBlockCheckpointFromDeploys[F](
                 b,
                 genesis,
                 dag
               )
    } yield (result.postStateHash, result.deploysForBlock)

  def injectPostStateHash[F[_]: Monad: BlockStorage: IndexedDagStorage](
      id: Int,
      b: Block,
      postGenStateHash: StateHash,
      processedDeploys: Seq[ProcessedDeploy]
  ): F[Unit] = {
    val updatedBlockPostState = b.getHeader.getState.withPostStateHash(postGenStateHash)
    val updatedBlockHeader =
      b.getHeader.withState(updatedBlockPostState)
    val updatedBlockBody = b.getBody.withDeploys(processedDeploys)
    // NOTE: Storing this under the original block hash.
    val updatedBlock =
      ProtoUtil.unsignedBlockProto(updatedBlockBody, updatedBlockHeader).withBlockHash(b.blockHash)
    BlockStorage[F].put(b.blockHash, updatedBlock, Seq.empty) *>
      IndexedDagStorage[F].inject(id, updatedBlock)
  }

  private[casper] def computeBlockCheckpointFromDeploys[F[_]: Sync: BlockStorage: Log: ExecutionEngineService](
      b: Block,
      genesis: Block,
      dag: DagRepresentation[F]
  ): F[DeploysCheckpoint] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)

      deploys = ProtoUtil.deploys(b).flatMap(_.deploy)

      _ = assert(
        parents.nonEmpty || (parents.isEmpty && b == genesis),
        "Received a different genesis block."
      )
      merged <- ExecEngineUtil.merge[F](parents, dag)
      result <- computeDeploysCheckpoint[F](
                 merged,
                 deploys,
                 b.getHeader.timestamp,
                 ProtocolVersion(1)
               )
    } yield result

}

trait BlockGenerator {
  def createBlock[F[_]: Monad: Time: BlockStorage: IndexedDagStorage](
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      postStateHash: ByteString = ByteString.EMPTY,
      chainId: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY
  ): F[Block] =
    for {
      now <- Time[F].currentMillis
      postState = Block
        .GlobalState()
        .withPreStateHash(preStateHash)
        .withPostStateHash(postStateHash)
        .withBonds(bonds)
      body = Block.Body().withDeploys(deploys)
      serializedJustifications = justifications.toList.map {
        case (creator: Validator, latestBlockHash: BlockHash) =>
          Block.Justification(creator, latestBlockHash)
      }
      header = ProtoUtil
        .blockHeader(
          body,
          parentsHashList,
          serializedJustifications,
          postState,
          rank = 0,
          protocolVersion = 1,
          timestamp = now,
          chainId = chainId
        )
        .withValidatorPublicKey(creator)
      block               = ProtoUtil.unsignedBlockProto(body, header)
      serializedBlockHash = block.blockHash
      modifiedBlock       <- IndexedDagStorage[F].insertIndexed(block)
      // NOTE: Block hash should be recalculated.
      _ <- BlockStorage[F]
            .put(serializedBlockHash, modifiedBlock, Seq.empty)
    } yield modifiedBlock
}
