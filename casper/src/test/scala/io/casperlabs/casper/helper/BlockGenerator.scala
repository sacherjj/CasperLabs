package io.casperlabs.casper.helper

import cats._
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{computeDeploysCheckpoint, StateHash}
import io.casperlabs.casper.util.execengine.{DeploysCheckpoint, ExecEngineUtil}
import io.casperlabs.crypto.Keys
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, IndexedDagStorage}
import io.casperlabs.storage.deploy.DeployStorage
import monix.eval.Task

import scala.collection.immutable.HashMap
import scala.language.higherKinds

object BlockGenerator {
  implicit val timeEff = new LogicalTime[Task]()

  def updateChainWithBlockStateUpdate[F[_]: Sync: BlockStorage: IndexedDagStorage: DeployStorage: ExecutionEngineService: Log](
      id: Int,
      genesis: Block
  ): F[Block] =
    for {
      b   <- IndexedDagStorage[F].lookupByIdUnsafe(id)
      dag <- IndexedDagStorage[F].getRepresentation
      blockCheckpoint <- computeBlockCheckpointFromDeploys[F](
                          b,
                          genesis,
                          dag
                        )
      _ <- injectPostStateHash[F](
            id,
            b,
            blockCheckpoint.postStateHash,
            blockCheckpoint.deploysForBlock
          )
    } yield b

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

  private[casper] def computeBlockCheckpointFromDeploys[F[_]: Sync: BlockStorage: DeployStorage: Log: ExecutionEngineService](
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
      implicit0(deploySelection: DeploySelection[F]) = DeploySelection.create[F](
        5 * 1024 * 1024
      )
      _ <- DeployStorage[F].addAsPending(deploys.toList)
      result <- computeDeploysCheckpoint[F](
                 merged,
                 deploys.map(_.deployHash).toSet,
                 b.getHeader.timestamp,
                 ProtocolVersion(1)
               )
    } yield result

}

trait BlockGenerator {
  def createBlock[F[_]: Monad: Time: IndexedDagStorage](
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      postStateHash: ByteString = ByteString.EMPTY,
      chainId: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY,
      roleType: Block.Role = Block.Role.BLOCK
  ): F[Block] =
    for {
      now <- Time[F].currentMillis
      postState = Block
        .GlobalState()
        .withPreStateHash(preStateHash)
        .withPostStateHash(postStateHash)
        .withBonds(bonds)
      body = Block.Body().withDeploys(deploys)
      dag  <- IndexedDagStorage[F].getRepresentation
      // Every parent should also include in the justification,by doing this we can avoid passing parameter justifications when creating block in test
      updatedJustifications <- parentsHashList.toList.foldLeftM(justifications) {
                                case (acc, b) =>
                                  dag
                                    .lookup(b)
                                    .map(
                                      _.fold(acc) { block =>
                                        if (acc.contains(block.validatorId)) {
                                          acc
                                        } else {
                                          acc + (block.validatorId -> block.messageHash)
                                        }
                                      }
                                    )
                              }
      serializedJustifications = updatedJustifications.toList.map {
        case (creator: Validator, latestBlockHash: BlockHash) =>
          Block.Justification(creator, latestBlockHash)
      }
      validatorLatestMsg <- dag.latestMessage(creator)
      validatorSeqNum    = validatorLatestMsg.fold(0)(_.validatorMsgSeqNum + 1)
      header = ProtoUtil
        .blockHeader(
          body,
          Keys.PublicKey(creator.toByteArray),
          parentsHashList,
          serializedJustifications,
          postState,
          0L,
          validatorSeqNum,
          1,
          now,
          chainId
        )
        .withRoleType(roleType)
      block                = ProtoUtil.unsignedBlockProto(body, header)
      unsignedIndexedBlock <- IndexedDagStorage[F].index(block)
      signedIndexedBlock = ProtoUtil.unsignedBlockProto(
        unsignedIndexedBlock.getBody,
        unsignedIndexedBlock.getHeader
      )
    } yield signedIndexedBlock

  def createAndStoreBlock[F[_]: Monad: Time: BlockStorage: IndexedDagStorage](
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
      block <- createBlock[F](
                parentsHashList = parentsHashList,
                creator = creator,
                bonds = bonds,
                justifications = justifications,
                deploys = deploys,
                postStateHash = postStateHash,
                chainId = chainId,
                preStateHash = preStateHash
              )
      _ <- BlockStorage[F].put(block.blockHash, block, Seq.empty)
    } yield block
}
