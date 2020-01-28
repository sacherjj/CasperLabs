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
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{computeDeploysCheckpoint, StateHash}
import io.casperlabs.casper.util.execengine.{DeploysCheckpoint, ExecEngineUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, IndexedDagStorage}
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageWriter}
import monix.eval.Task

import scala.collection.immutable.HashMap
import scala.language.higherKinds

object BlockGenerator {
  implicit val timeEff = new LogicalTime[Task]()

  def updateChainWithBlockStateUpdate[F[_]: Sync: BlockStorage: IndexedDagStorage: DeployStorage: ExecutionEngineService: Log: Metrics](
      id: Int
  ): F[Block] =
    for {
      b   <- IndexedDagStorage[F].lookupByIdUnsafe(id)
      dag <- IndexedDagStorage[F].getRepresentation
      blockCheckpoint <- computeBlockCheckpointFromDeploys[F](
                          b,
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
    BlockStorage[F].put(b.blockHash, updatedBlock, Map.empty) *>
      IndexedDagStorage[F].inject(id, updatedBlock)
  }

  private[casper] def computeBlockCheckpointFromDeploys[F[_]: Sync: BlockStorage: DeployStorage: Log: ExecutionEngineService: Metrics](
      b: Block,
      dag: DagRepresentation[F]
  ): F[DeploysCheckpoint] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)
      deploys = ProtoUtil.deploys(b).values.flatMap(_.toList).flatMap(_.deploy)

      merged <- ExecutionEngineServiceStub.merge[F](parents, dag)
      implicit0(deploySelection: DeploySelection[F]) = DeploySelection.create[F](
        5 * 1024 * 1024
      )
      _ <- DeployStorageWriter[F].addAsPending(deploys.toList)
      result <- computeDeploysCheckpoint[F](
                 merged,
                 fs2.Stream.fromIterator(deploys.toIterator),
                 b.getHeader.timestamp,
                 ProtocolVersion(1),
                 rank = 0,
                 upgrades = Nil
               )
    } yield result

}

trait BlockGenerator {
  def createMessage[F[_]: MonadThrowable: Time: IndexedDagStorage](
      parentsHashList: Seq[BlockHash],
      keyBlockHash: ByteString = ByteString.EMPTY,
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty,
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      postStateHash: ByteString = ByteString.EMPTY,
      chainName: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY,
      messageType: Block.MessageType = Block.MessageType.BLOCK
  ): F[Block] =
    createMessageNew[F](
      parentsHashList,
      keyBlockHash,
      creator,
      bonds,
      justifications.mapValues(Set(_)),
      deploys,
      postStateHash,
      chainName,
      preStateHash,
      messageType
    )

  def createMessageNew[F[_]: MonadThrowable: Time: IndexedDagStorage](
      parentsHashList: Seq[BlockHash],
      keyBlockHash: BlockHash,
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, Set[BlockHash]] = HashMap.empty,
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      postStateHash: ByteString = ByteString.EMPTY,
      chainName: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY,
      messageType: Block.MessageType = Block.MessageType.BLOCK,
      maybeValidatorPrevBlockHash: Option[BlockHash] = None,
      maybeValidatorBlockSeqNum: Option[Int] = None
  ): F[Block] = {
    if (messageType == Block.MessageType.BALLOT)
      require(parentsHashList.size == 1, "A ballot can only have one parent.")
    for {
      now <- Time[F].currentMillis
      postState = Block
        .GlobalState()
        .withPreStateHash(preStateHash)
        .withPostStateHash(postStateHash)
        .withBonds(bonds)
      body = Block.Body().withDeploys(deploys)
      dag  <- IndexedDagStorage[F].getRepresentation
      // Every parent should also be included in the justifications;
      // By doing this we can avoid passing parameter justifications when creating block in test
      updatedJustifications <- parentsHashList.toList.foldLeftM(justifications) {
                                case (acc, b) =>
                                  dag
                                    .lookup(b)
                                    .map(
                                      _.fold(acc) { block =>
                                        acc
                                          .get(block.validatorId)
                                          .fold(
                                            acc.updated(block.validatorId, Set(block.messageHash))
                                          )(
                                            s =>
                                              acc
                                                .updated(block.validatorId, s + block.messageHash)
                                          )
                                      }
                                    )
                              }
      serializedJustifications = updatedJustifications.toList.flatMap {
        case (creator: Validator, hashes) =>
          hashes.map(hash => Block.Justification(creator, hash))
      }
      // Allow for indirect justifications by looking it up in the DAG.
      validatorPrevBlockHash <- maybeValidatorPrevBlockHash.map(_.pure[F]).getOrElse {
                                 updatedJustifications.values.flatten.toList
                                   .traverse(dag.lookup)
                                   .map(_.flatten)
                                   .flatMap(
                                     DagOperations
                                       .toposortJDagDesc(dag, _)
                                       .find(_.validatorId == creator)
                                   )
                                   .map(_.fold(ByteString.EMPTY)(_.messageHash))
                               }
      validatorSeqNum <- maybeValidatorBlockSeqNum.map(_.pure[F]).getOrElse {
                          if (parentsHashList.isEmpty) 0.pure[F]
                          else
                            ProtoUtil.nextValidatorBlockSeqNum(dag, validatorPrevBlockHash)
                        }
      rank <- if (parentsHashList.isEmpty) 0L.pure[F]
             else
               updatedJustifications.values.toList
                 .flatTraverse(_.toList.traverse(dag.lookup(_)))
                 .map(_.flatten)
                 .map(ProtoUtil.nextRank(_))
      header = ProtoUtil
        .blockHeader(
          body,
          Keys.PublicKey(creator.toByteArray),
          parentsHashList,
          serializedJustifications,
          postState,
          rank,
          validatorSeqNum,
          validatorPrevBlockHash,
          protocolVersion = ProtocolVersion(1),
          timestamp = now,
          chainName = chainName,
          keyBlockHash = keyBlockHash
        )
        .withMessageType(messageType)
      block = ProtoUtil.unsignedBlockProto(body, header)
      _     <- IndexedDagStorage[F].index(block)
    } yield block
  }

  def createAndStoreMessage[F[_]: MonadThrowable: Time: BlockStorage: IndexedDagStorage](
      parentsHashList: Seq[BlockHash],
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash],
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      postStateHash: ByteString = ByteString.EMPTY,
      chainName: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY,
      maybeValidatorPrevBlockHash: Option[BlockHash] = None,
      maybeValidatorBlockSeqNum: Option[Int] = None,
      keyBlockHash: BlockHash = ByteString.EMPTY,
      messageType: Block.MessageType = Block.MessageType.BLOCK
  ): F[Block] = {
    if (messageType == Block.MessageType.BALLOT)
      require(parentsHashList.size == 1, "A ballot can only have one parent.")
    createAndStoreMessageNew[F](
      parentsHashList,
      keyBlockHash,
      creator,
      bonds,
      justifications.mapValues(Set(_)),
      deploys,
      postStateHash,
      chainName,
      preStateHash,
      maybeValidatorPrevBlockHash,
      maybeValidatorBlockSeqNum,
      messageType
    )
  }

  def createAndStoreMessageNew[F[_]: MonadThrowable: Time: BlockStorage: IndexedDagStorage](
      parentsHashList: Seq[BlockHash],
      keyBlockHash: ByteString,
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, Set[BlockHash]],
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      postStateHash: ByteString = ByteString.EMPTY,
      chainName: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY,
      maybeValidatorPrevBlockHash: Option[BlockHash] = None,
      maybeValidatorBlockSeqNum: Option[Int] = None,
      messageType: Block.MessageType = Block.MessageType.BLOCK
  ): F[Block] =
    for {
      block <- createMessageNew[F](
                parentsHashList = parentsHashList,
                creator = creator,
                bonds = bonds,
                justifications = justifications,
                deploys = deploys,
                postStateHash = postStateHash,
                chainName = chainName,
                preStateHash = preStateHash,
                maybeValidatorPrevBlockHash = maybeValidatorPrevBlockHash,
                maybeValidatorBlockSeqNum = maybeValidatorBlockSeqNum,
                keyBlockHash = keyBlockHash,
                messageType = messageType
              )
      _ <- BlockStorage[F].put(block.blockHash, block, Map.empty)
    } yield block

  // Same as createAndStoreBlock but works with full models for building DAGs easier.
  def createAndStoreBlockFull[F[_]: MonadThrowable: Time: BlockStorage: IndexedDagStorage](
      creator: Validator,
      parents: Seq[Block],
      justifications: Seq[Block],
      bonds: Seq[Bond] = Seq.empty[Bond],
      deploys: Seq[ProcessedDeploy] = Seq.empty[ProcessedDeploy],
      chainName: String = "casperlabs",
      preStateHash: ByteString = ByteString.EMPTY,
      postStateHash: ByteString = ByteString.EMPTY,
      maybeValidatorPrevBlockHash: Option[BlockHash] = None,
      maybeValidatorBlockSeqNum: Option[Int] = None
  ): F[Block] =
    createAndStoreMessage[F](
      parentsHashList = parents.map(_.blockHash),
      creator = creator,
      bonds = bonds,
      justifications = justifications.map(b => b.getHeader.validatorPublicKey -> b.blockHash).toMap,
      deploys = deploys,
      postStateHash = postStateHash,
      chainName = chainName,
      preStateHash = preStateHash,
      maybeValidatorPrevBlockHash = maybeValidatorPrevBlockHash,
      maybeValidatorBlockSeqNum = maybeValidatorBlockSeqNum
    )
}
