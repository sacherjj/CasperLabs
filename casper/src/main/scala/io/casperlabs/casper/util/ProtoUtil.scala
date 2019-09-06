package io.casperlabs.casper.util

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.{ByteString, Int32Value, StringValue}
import io.casperlabs.blockstorage.{BlockMetadata, BlockStorage, DagRepresentation}
import io.casperlabs.casper.EquivocationRecord.SequenceNumber
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.{PrettyPrinter, ValidatorIdentity}
import io.casperlabs.casper.consensus._
import Block.Justification
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.catscontrib.ski.id
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.Abi
import java.util.NoSuchElementException

import scala.collection.immutable

object ProtoUtil {
  /*
   * c is in the blockchain of b iff c == b or c is in the blockchain of the main parent of b
   */
  // TODO: Move into DAG and remove corresponding param once that is moved over from simulator
  def isInMainChain[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockMetadata: BlockMetadata,
      targetBlockHash: BlockHash
  ): F[Boolean] =
    if (candidateBlockMetadata.blockHash == targetBlockHash) {
      true.pure[F]
    } else {
      for {
        targetBlockOpt <- dag.lookup(targetBlockHash)
        result <- targetBlockOpt match {
                   case Some(targetBlockMeta) =>
                     if (targetBlockMeta.rank <= candidateBlockMetadata.rank)
                       false.pure[F]
                     else {
                       targetBlockMeta.parents.headOption match {
                         case Some(mainParentHash) =>
                           isInMainChain(dag, candidateBlockMetadata, mainParentHash)
                         case None => false.pure[F]
                       }
                     }
                   case None => false.pure[F]
                 }
      } yield result
    }

  // If targetBlockHash is main descendant of candidateBlockHash, then
  // it means targetBlockHash vote candidateBlockHash.
  def isInMainChain[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      targetBlockHash: BlockHash
  ): F[Boolean] =
    for {
      candidateBlockMetadata <- dag.lookup(candidateBlockHash)
      result                 <- isInMainChain(dag, candidateBlockMetadata.get, targetBlockHash)
    } yield result

  // calculate which branch of latestFinalizedBlockHash that the newBlockHash vote for
  def votedBranch[F[_]: Monad](
      dag: DagRepresentation[F],
      latestFinalizeBlockHash: BlockHash,
      newBlockHash: BlockHash
  ): F[Option[BlockHash]] =
    for {
      newBlock             <- dag.lookup(newBlockHash)
      latestFinalizedBlock <- dag.lookup(latestFinalizeBlockHash)
      r                    <- votedBranch(dag, latestFinalizedBlock.get, newBlock.get)
    } yield r

  def votedBranch[F[_]: Monad](
      dag: DagRepresentation[F],
      latestFinalizedBlock: BlockMetadata,
      newBlock: BlockMetadata
  ): F[Option[BlockHash]] =
    if (newBlock.rank <= latestFinalizedBlock.rank) {
      none[BlockHash].pure[F]
    } else {
      for {
        result <- newBlock.parents.headOption match {
                   case Some(mainParentHash) =>
                     if (mainParentHash == latestFinalizedBlock.blockHash) {
                       newBlock.blockHash.some.pure[F]
                     } else {
                       dag
                         .lookup(mainParentHash)
                         .flatMap(b => votedBranch(dag, latestFinalizedBlock, b.get))
                     }
                   case None => none[BlockHash].pure[F]
                 }
      } yield result
    }

  def getMainChainUntilDepth[F[_]: MonadThrowable: BlockStorage](
      estimate: Block,
      acc: IndexedSeq[Block],
      depth: Int
  ): F[IndexedSeq[Block]] = {
    val parentsHashes       = ProtoUtil.parentHashes(estimate)
    val maybeMainParentHash = parentsHashes.headOption
    for {
      mainChain <- maybeMainParentHash match {
                    case Some(mainParentHash) =>
                      for {
                        updatedEstimate <- unsafeGetBlock[F](mainParentHash)
                        depthDelta      = blockNumber(updatedEstimate) - blockNumber(estimate)
                        newDepth        = depth + depthDelta.toInt
                        mainChain <- if (newDepth <= 0) {
                                      (acc :+ estimate).pure[F]
                                    } else {
                                      getMainChainUntilDepth[F](
                                        updatedEstimate,
                                        acc :+ estimate,
                                        newDepth
                                      )
                                    }
                      } yield mainChain
                    case None => (acc :+ estimate).pure[F]
                  }
    } yield mainChain
  }

  def unsafeGetBlockSummary[F[_]: MonadThrowable: BlockStorage](hash: BlockHash): F[BlockSummary] =
    for {
      maybeBlock <- BlockStorage[F].getBlockSummary(hash)
      block <- maybeBlock match {
                case Some(b) => b.pure[F]
                case None =>
                  MonadThrowable[F].raiseError(
                    new NoSuchElementException(
                      s"BlockStorage is missing hash ${PrettyPrinter.buildString(hash)}"
                    )
                  )
              }
    } yield block

  def unsafeGetBlock[F[_]: MonadThrowable: BlockStorage](hash: BlockHash): F[Block] =
    for {
      maybeBlock <- BlockStorage[F].getBlockMessage(hash)
      block <- maybeBlock match {
                case Some(b) => b.pure[F]
                case None =>
                  MonadThrowable[F].raiseError(
                    new NoSuchElementException(
                      s"BlockStorage is missing hash ${PrettyPrinter.buildString(hash)}"
                    )
                  )
              }
    } yield block

  def calculateRank(justificationMsgs: Seq[BlockMetadata]): Long =
    1L + justificationMsgs.foldLeft(-1L) {
      case (acc, blockMetadata) => math.max(acc, blockMetadata.rank)
    }

  def creatorJustification(block: Block): Option[Justification] =
    creatorJustification(block.getHeader)

  def creatorJustification(header: Block.Header): Option[Justification] =
    header.justifications
      .find {
        case Justification(validator: Validator, _) =>
          validator == header.validatorPublicKey
      }

  def findCreatorJustificationAncestorWithSeqNum[F[_]: Monad: BlockStorage](
      b: Block,
      seqNum: SequenceNumber
  ): F[Option[Block]] =
    if (b.getHeader.validatorBlockSeqNum == seqNum) {
      Option[Block](b).pure[F]
    } else {
      DagOperations
        .bfTraverseF(List(b)) { block =>
          getCreatorJustificationAsList[F](block, block.getHeader.validatorPublicKey)
        }
        .find(_.getHeader.validatorBlockSeqNum == seqNum)
    }

  // TODO: Replace with getCreatorJustificationAsListUntilGoal
  def getCreatorJustificationAsList[F[_]: Monad: BlockStorage](
      block: Block,
      validator: Validator,
      goalFunc: Block => Boolean = _ => false
  ): F[List[Block]] = {
    val maybeCreatorJustificationHash =
      block.getHeader.justifications.find(_.validatorPublicKey == validator)

    maybeCreatorJustificationHash match {
      case Some(creatorJustificationHash) =>
        for {
          maybeCreatorJustification <- BlockStorage[F].getBlockMessage(
                                        creatorJustificationHash.latestBlockHash
                                      )
          maybeCreatorJustificationAsList = maybeCreatorJustification match {
            case Some(creatorJustification) =>
              if (goalFunc(creatorJustification)) {
                List.empty[Block]
              } else {
                List(creatorJustification)
              }
            case None =>
              List.empty[Block]
          }
        } yield maybeCreatorJustificationAsList
      case None => List.empty[Block].pure[F]
    }
  }

  /**
    * Since the creator justification is unique
    * we don't need to return a list. However, the bfTraverseF
    * requires a list to be returned. When we reach the goalFunc,
    * we return an empty list.
    */
  def getCreatorJustificationAsListUntilGoalInMemory[F[_]: Monad](
      dag: DagRepresentation[F],
      blockHash: BlockHash,
      goalFunc: BlockHash => Boolean = _ => false
  ): F[List[BlockHash]] =
    (for {
      meta <- OptionT(dag.lookup(blockHash))
      creatorJustificationHash <- OptionT.fromOption[F](
                                   meta.justifications
                                     .find(
                                       _.validatorPublicKey == meta.validatorPublicKey
                                     )
                                     .map(_.latestBlockHash)
                                 )
      creatorJustification <- OptionT(dag.lookup(creatorJustificationHash))
      creatorJustificationAsList = if (goalFunc(creatorJustification.blockHash)) {
        List.empty[BlockHash]
      } else {
        List(creatorJustification.blockHash)
      }
    } yield creatorJustificationAsList).fold(List.empty[BlockHash])(id)

  def weightMap(block: Block): Map[ByteString, Long] =
    weightMap(block.getHeader)

  def weightMap(header: Block.Header): Map[ByteString, Long] =
    header.getState.bonds.map {
      case Bond(validator, stake) => validator -> stake
    }.toMap

  def weightMapTotal(weights: Map[ByteString, Long]): Long =
    weights.values.sum

  def minTotalValidatorWeight[F[_]: Monad](
      dag: DagRepresentation[F],
      blockHash: BlockHash,
      maxCliqueMinSize: Int
  ): F[Long] =
    dag.lookup(blockHash).map { blockMetadataOpt =>
      val sortedWeights = blockMetadataOpt.get.weightMap.values.toVector.sorted
      sortedWeights.take(maxCliqueMinSize).sum
    }

  private def mainParent[F[_]: Monad: BlockStorage](
      header: Block.Header
  ): F[Option[BlockSummary]] = {
    val maybeParentHash = header.parentHashes.headOption
    maybeParentHash match {
      case Some(parentHash) => BlockStorage[F].getBlockSummary(parentHash)
      case None             => none[BlockSummary].pure[F]
    }
  }

  /** Computes block's score by the [validator].
    *
    * @param dag
    * @param blockHash Block's hash
    * @param validator Validator that produced the block
    * @tparam F
    * @return Weight `validator` put behind the block
    */
  def weightFromValidatorByDag[F[_]: Monad](
      dag: DagRepresentation[F],
      blockHash: BlockHash,
      validator: Validator
  ): F[Long] =
    for {
      blockMetadata  <- dag.lookup(blockHash)
      blockParentOpt = blockMetadata.get.parents.headOption
      resultOpt <- blockParentOpt.traverse { bh =>
                    dag.lookup(bh).map(_.get.weightMap.getOrElse(validator, 0L))
                  }
      result = resultOpt match {
        case Some(result) => result
        case None         => blockMetadata.get.weightMap.getOrElse(validator, 0L)
      }
    } yield result

  def weightFromValidator[F[_]: Monad: BlockStorage](
      header: Block.Header,
      validator: Validator
  ): F[Long] =
    for {
      maybeMainParent <- mainParent[F](header)
      weightFromValidator = maybeMainParent
        .map(p => weightMap(p.getHeader).getOrElse(validator, 0L))
        .getOrElse(weightMap(header).getOrElse(validator, 0L)) //no parents means genesis -- use itself
    } yield weightFromValidator

  def weightFromValidator[F[_]: Monad: BlockStorage](
      b: Block,
      validator: ByteString
  ): F[Long] =
    weightFromValidator[F](b.getHeader, validator)

  def weightFromSender[F[_]: Monad: BlockStorage](b: Block): F[Long] =
    weightFromValidator[F](b, b.getHeader.validatorPublicKey)

  def weightFromSender[F[_]: Monad: BlockStorage](header: Block.Header): F[Long] =
    weightFromValidator[F](header, header.validatorPublicKey)

  def mainParentWeightMap[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Map[BlockHash, Long]] =
    dag.lookup(candidateBlockHash).flatMap { blockOpt =>
      blockOpt.get.parents.headOption match {
        case Some(parent) => dag.lookup(parent).map(_.get.weightMap)
        case None         => blockOpt.get.weightMap.pure[F]
      }
    }

  def parentHashes(b: Block): Seq[ByteString] =
    b.getHeader.parentHashes

  def unsafeGetParents[F[_]: MonadThrowable: BlockStorage](b: Block): F[List[Block]] =
    ProtoUtil.parentHashes(b).toList.traverse { parentHash =>
      ProtoUtil.unsafeGetBlock[F](parentHash)
    } handleErrorWith {
      case ex: NoSuchElementException =>
        MonadThrowable[F].raiseError {
          new NoSuchElementException(
            s"Could not retrieve parents of ${PrettyPrinter.buildString(b.blockHash)}: ${ex.getMessage}"
          )
        }
    }

  def containsDeploy(b: Block, accountPublicKey: ByteString, timestamp: Long): Boolean =
    deploys(b).toStream
      .flatMap(_.deploy)
      .exists(
        d => d.getHeader.accountPublicKey == accountPublicKey && d.getHeader.timestamp == timestamp
      )

  def deploys(b: Block): Seq[Block.ProcessedDeploy] =
    b.getBody.deploys

  def postStateHash(b: Block): ByteString =
    b.getHeader.getState.postStateHash

  def preStateHash(b: Block): ByteString =
    b.getHeader.getState.preStateHash

  def bonds(b: Block): Seq[Bond] =
    b.getHeader.getState.bonds

  def bonds(b: BlockSummary): Seq[Bond] =
    b.getHeader.getState.bonds

  def blockNumber(b: Block): Long =
    b.getHeader.rank

  def toJustification(
      latestMessages: collection.Map[Validator, BlockMetadata]
  ): Seq[Justification] =
    latestMessages.toSeq.map {
      case (validator, blockMetadata) =>
        Block
          .Justification()
          .withValidatorPublicKey(validator)
          .withLatestBlockHash(blockMetadata.blockHash)
    }

  def toLatestMessageHashes(
      justifications: Seq[Justification]
  ): immutable.Map[Validator, BlockHash] =
    justifications.foldLeft(Map.empty[Validator, BlockHash]) {
      case (acc, Justification(validator, block)) =>
        acc.updated(validator, block)
    }

  def toLatestMessage[F[_]: MonadThrowable: BlockStorage](
      justifications: Seq[Justification]
  ): F[immutable.Map[Validator, BlockMetadata]] =
    justifications.toList.foldM(Map.empty[Validator, BlockMetadata]) {
      case (acc, Justification(validator, hash)) =>
        for {
          block <- ProtoUtil.unsafeGetBlock[F](hash)
        } yield acc.updated(validator, BlockMetadata.fromBlock(block))
    }

  def protoHash[A <: scalapb.GeneratedMessage](protoSeq: A*): ByteString =
    protoSeqHash(protoSeq)

  def protoSeqHash[A <: scalapb.GeneratedMessage](protoSeq: Seq[A]): ByteString =
    hashByteArrays(protoSeq.map(_.toByteArray): _*)

  def hashByteArrays(items: Array[Byte]*): ByteString =
    ByteString.copyFrom(Blake2b256.hash(Array.concat(items: _*)))

  def blockHeader(
      body: Block.Body,
      parentHashes: Seq[ByteString],
      justifications: Seq[Justification],
      state: Block.GlobalState,
      rank: Long,
      protocolVersion: Long,
      timestamp: Long,
      chainId: String
  ): Block.Header =
    Block
      .Header()
      .withParentHashes(parentHashes)
      .withJustifications(justifications)
      .withDeployCount(body.deploys.size)
      .withState(state)
      .withRank(rank)
      .withProtocolVersion(protocolVersion)
      .withTimestamp(timestamp)
      .withChainId(chainId)
      .withBodyHash(protoHash(body))

  def unsignedBlockProto(
      body: Block.Body,
      header: Block.Header
  ): Block = {
    val headerWithBodyHash = header
      .withBodyHash(protoHash(body))

    val blockHash = protoHash(headerWithBodyHash)

    Block()
      .withBlockHash(blockHash)
      .withHeader(headerWithBodyHash)
      .withBody(body)
  }

  def signBlock[F[_]: Applicative](
      block: Block,
      dag: DagRepresentation[F],
      pk: PublicKey,
      sk: PrivateKey,
      sigAlgorithm: SignatureAlgorithm
  ): F[Block] = {
    val validator = ByteString.copyFrom(pk)
    for {
      latestMessageOpt <- dag.latestMessage(validator)
      seqNum           = latestMessageOpt.fold(-1)(_.validatorBlockSeqNum) + 1
      header = {
        assert(block.header.isDefined, "A block without a header doesn't make sense")
        block.getHeader
          .withValidatorPublicKey(validator)
          .withValidatorBlockSeqNum(seqNum)
      }
      blockHash = protoHash(header)
      sig       = ByteString.copyFrom(sigAlgorithm.sign(blockHash.toByteArray, sk))
      signedBlock = block
        .withBlockHash(blockHash)
        .withHeader(header)
        .withSignature(
          Signature()
            .withSigAlgorithm(sigAlgorithm.name)
            .withSig(sig)
        )
    } yield signedBlock
  }

  def stringToByteString(string: String): ByteString =
    ByteString.copyFrom(Base16.decode(string))

  def basicDeploy[F[_]: Monad: Time](
      nonce: Long
  ): F[Deploy] =
    Time[F].currentMillis.map { now =>
      basicDeploy(now, ByteString.EMPTY, nonce)
    }

  // This is only used for tests.
  def basicDeploy(
      timestamp: Long,
      sessionCode: ByteString = ByteString.EMPTY,
      nonce: Long = 0,
      accountPublicKey: ByteString = ByteString.EMPTY
  ): Deploy = {
    val b = Deploy
      .Body()
      .withSession(Deploy.Code().withWasm(sessionCode))
      .withPayment(Deploy.Code().withWasm(sessionCode))
    val h = Deploy
      .Header()
      .withAccountPublicKey(accountPublicKey)
      .withTimestamp(timestamp)
      .withNonce(nonce)
      .withBodyHash(protoHash(b))
    Deploy()
      .withDeployHash(protoHash(h))
      .withHeader(h)
      .withBody(b)
  }

  def basicProcessedDeploy[F[_]: Monad: Time](id: Long): F[Block.ProcessedDeploy] =
    basicDeploy[F](id).map(deploy => Block.ProcessedDeploy(deploy = Some(deploy)))

  def sourceDeploy(source: String, timestamp: Long): Deploy =
    sourceDeploy(ByteString.copyFromUtf8(source), timestamp)

  def sourceDeploy(sessionCode: ByteString, timestamp: Long): Deploy =
    basicDeploy(timestamp, sessionCode)

  // https://casperlabs.atlassian.net/browse/EE-283
  // We are hardcoding exchange rate for DEV NET at 10:1
  // (1 gas costs you 10 motes).
  // Later, post DEV NET, conversion rate will be part of a deploy.
  val GAS_PRICE = 10L

  def deployDataToEEDeploy[F[_]: MonadThrowable](d: Deploy): F[ipc.DeployItem] = {
    def toPayload(maybeCode: Option[Deploy.Code]): F[Option[ipc.DeployPayload]] =
      maybeCode match {
        case None       => none[ipc.DeployPayload].pure[F]
        case Some(code) => (deployCodeToDeployPayload[F](code).map(Some(_)))
      }

    for {
      session <- toPayload(d.getBody.session)
      payment <- toPayload(d.getBody.payment)
    } yield {
      ipc.DeployItem(
        address = d.getHeader.accountPublicKey,
        session = session,
        payment = payment,
        gasPrice = GAS_PRICE,
        nonce = d.getHeader.nonce,
        authorizationKeys = d.approvals.map(_.approverPublicKey)
      )
    }
  }

  def deployCodeToDeployPayload[F[_]: MonadThrowable](code: Deploy.Code): F[ipc.DeployPayload] = {
    val argsF: F[ByteString] = if (code.args.nonEmpty) {
      MonadThrowable[F]
        .fromTry(Abi.args(code.args.map(_.getValue: Abi.Serializable[_]): _*))
        .map(ByteString.copyFrom(_))
    } else code.abiArgs.pure[F]

    argsF.map { args =>
      val payload = code.contract match {
        case Deploy.Code.Contract.Wasm(wasm) =>
          ipc.DeployPayload.Payload.DeployCode(ipc.DeployCode(wasm, args))
        case Deploy.Code.Contract.Hash(hash) =>
          ipc.DeployPayload.Payload.StoredContractHash(ipc.StoredContractHash(hash, args))
        case Deploy.Code.Contract.Name(name) =>
          ipc.DeployPayload.Payload.StoredContractName(ipc.StoredContractName(name, args))
        case Deploy.Code.Contract.Uref(uref) =>
          ipc.DeployPayload.Payload.StoredContractUref(ipc.StoredContractURef(uref, args))
        case Deploy.Code.Contract.Empty =>
          ipc.DeployPayload.Payload.Empty
      }
      ipc.DeployPayload(payload)
    }
  }

  def dependenciesHashesOf(b: Block): List[BlockHash] = {
    val missingParents = parentHashes(b).toSet
    val missingJustifications = b.getHeader.justifications
      .map(_.latestBlockHash)
      .toSet
    (missingParents union missingJustifications).toList
  }

  def createdBy(validatorId: ValidatorIdentity, block: Block): Boolean =
    block.getHeader.validatorPublicKey == ByteString.copyFrom(validatorId.publicKey)

  implicit class DeployOps(d: Deploy) {
    def incrementNonce(): Deploy =
      this.withNonce(d.header.get.nonce + 1)

    def withNonce(newNonce: Long): Deploy =
      d.withHeader(d.header.get.withNonce(newNonce))
  }

  def randomAccountAddress(): ByteString =
    ByteString.copyFrom(scala.util.Random.nextString(32), "UTF-8")
}
