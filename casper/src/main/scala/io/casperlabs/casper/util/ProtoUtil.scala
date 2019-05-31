package io.casperlabs.casper.util

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.{ByteString, Int32Value, StringValue}
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.EquivocationRecord.SequenceNumber
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.consensus._, Block.Justification
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.catscontrib.ski.id
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.blockstorage.BlockMetadata
import io.casperlabs.shared.Time
import java.util.NoSuchElementException
import scala.collection.immutable

object ProtoUtil {
  /*
   * c is in the blockchain of b iff c == b or c is in the blockchain of the main parent of b
   */
  // TODO: Move into BlockDAG and remove corresponding param once that is moved over from simulator
  def isInMainChain[F[_]: Monad](
      dag: BlockDagRepresentation[F],
      candidateBlockHash: BlockHash,
      targetBlockHash: BlockHash
  ): F[Boolean] =
    if (candidateBlockHash == targetBlockHash) {
      true.pure[F]
    } else {
      for {
        targetBlockOpt <- dag.lookup(targetBlockHash)
        result <- targetBlockOpt match {
                   case Some(targetBlockMeta) =>
                     targetBlockMeta.parents.headOption match {
                       case Some(mainParentHash) =>
                         isInMainChain(dag, candidateBlockHash, mainParentHash)
                       case None => false.pure[F]
                     }
                   case None => false.pure[F]
                 }
      } yield result
    }

  def getMainChainUntilDepth[F[_]: MonadThrowable: BlockStore](
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

  def unsafeGetBlock[F[_]: MonadThrowable: BlockStore](hash: BlockHash): F[Block] =
    for {
      maybeBlock <- BlockStore[F].getBlockMessage(hash)
      block <- maybeBlock match {
                case Some(b) => b.pure[F]
                case None =>
                  MonadThrowable[F].raiseError(
                    new NoSuchElementException(
                      s"BlockStore is missing hash ${PrettyPrinter.buildString(hash)}"
                    )
                  )
              }
    } yield block

  def creatorJustification(block: Block): Option[Justification] =
    block.getHeader.justifications
      .find {
        case Justification(validator: Validator, _) =>
          validator == block.getHeader.validatorPublicKey
      }

  def findCreatorJustificationAncestorWithSeqNum[F[_]: Monad: BlockStore](
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
  def getCreatorJustificationAsList[F[_]: Monad: BlockStore](
      block: Block,
      validator: Validator,
      goalFunc: Block => Boolean = _ => false
  ): F[List[Block]] = {
    val maybeCreatorJustificationHash =
      block.getHeader.justifications.find(_.validatorPublicKey == validator)

    maybeCreatorJustificationHash match {
      case Some(creatorJustificationHash) =>
        for {
          maybeCreatorJustification <- BlockStore[F].getBlockMessage(
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
      blockDag: BlockDagRepresentation[F],
      blockHash: BlockHash,
      validator: Validator,
      goalFunc: BlockHash => Boolean = _ => false
  ): F[List[BlockHash]] =
    (for {
      meta <- OptionT(blockDag.lookup(blockHash))
      creatorJustificationHash <- OptionT.fromOption[F](
                                   meta.justifications
                                     .find(
                                       _.validatorPublicKey == meta.validatorPublicKey
                                     )
                                     .map(_.latestBlockHash)
                                 )
      creatorJustification <- OptionT(blockDag.lookup(creatorJustificationHash))
      creatorJustificationAsList = if (goalFunc(creatorJustification.blockHash)) {
        List.empty[BlockHash]
      } else {
        List(creatorJustification.blockHash)
      }
    } yield creatorJustificationAsList).fold(List.empty[BlockHash])(id)

  def weightMap(block: Block): Map[ByteString, Long] =
    block.getHeader.getState.bonds.map {
      case Bond(validator, stake) => validator -> stake
    }.toMap

  def weightMapTotal(weights: Map[ByteString, Long]): Long =
    weights.values.sum

  def minTotalValidatorWeight[F[_]: Monad](
      blockDag: BlockDagRepresentation[F],
      blockHash: BlockHash,
      maxCliqueMinSize: Int
  ): F[Long] =
    blockDag.lookup(blockHash).map { blockMetadataOpt =>
      val sortedWeights = blockMetadataOpt.get.weightMap.values.toVector.sorted
      sortedWeights.take(maxCliqueMinSize).sum
    }

  def mainParent[F[_]: Monad: BlockStore](block: Block): F[Option[Block]] = {
    val maybeParentHash = block.getHeader.parentHashes.headOption
    maybeParentHash match {
      case Some(parentHash) => BlockStore[F].getBlockMessage(parentHash)
      case None             => none[Block].pure[F]
    }
  }

  def weightFromValidatorByDag[F[_]: Monad](
      dag: BlockDagRepresentation[F],
      blockHash: BlockHash,
      validator: Validator
  ): F[Long] =
    for {
      blockMetadata  <- dag.lookup(blockHash)
      blockParentOpt = blockMetadata.get.parents.headOption
      resultOpt <- blockParentOpt.traverse { bh =>
                    dag.lookup(bh).map(_.get.weightMap.getOrElse(validator, 0L))
                  }
      result <- resultOpt match {
                 case Some(result) => result.pure[F]
                 case None         => dag.lookup(blockHash).map(_.get.weightMap.getOrElse(validator, 0L))
               }
    } yield result

  def weightFromValidator[F[_]: Monad: BlockStore](
      b: Block,
      validator: ByteString
  ): F[Long] =
    for {
      maybeMainParent <- mainParent[F](b)
      weightFromValidator = maybeMainParent
        .map(weightMap(_).getOrElse(validator, 0L))
        .getOrElse(weightMap(b).getOrElse(validator, 0L)) //no parents means genesis -- use itself
    } yield weightFromValidator

  def weightFromSender[F[_]: Monad: BlockStore](b: Block): F[Long] =
    weightFromValidator[F](b, b.getHeader.validatorPublicKey)

  def parentHashes(b: Block): Seq[ByteString] =
    b.getHeader.parentHashes

  def unsafeGetParents[F[_]: MonadThrowable: BlockStore](b: Block): F[List[Block]] =
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

  def toLatestMessage[F[_]: MonadThrowable: BlockStore](
      justifications: Seq[Justification],
      dag: BlockDagRepresentation[F]
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
      dag: BlockDagRepresentation[F],
      pk: PublicKey,
      sk: PrivateKey,
      sigAlgorithm: SignatureAlgorithm
  ): F[Block] = {
    val validator = ByteString.copyFrom(pk)
    for {
      latestMessageOpt <- dag.latestMessage(validator)
      seqNum           = latestMessageOpt.fold(0)(_.validatorBlockSeqNum) + 1
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
      nonce: Int
  ): F[Deploy] =
    Time[F].currentMillis.map { now =>
      basicDeploy(now, ByteString.EMPTY).withNonce(nonce)
    }

  def basicDeploy(
      timestamp: Long,
      sessionCode: ByteString = ByteString.EMPTY
  ): Deploy = {
    val b = Deploy
      .Body()
      .withSession(Deploy.Code().withCode(sessionCode))
      .withPayment(Deploy.Code())
    val h = Deploy
      .Header()
      .withAccountPublicKey(ByteString.EMPTY)
      .withTimestamp(timestamp)
      .withBodyHash(protoHash(b))
    Deploy()
      .withDeployHash(protoHash(h))
      .withHeader(h)
      .withBody(b)
  }

  // TODO: it is for testing
  def basicProcessedDeploy[F[_]: Monad: Time](id: Int): F[Block.ProcessedDeploy] =
    basicDeploy[F](id).map(deploy => Block.ProcessedDeploy(deploy = Some(deploy)))

  def sourceDeploy(source: String, timestamp: Long, gasLimit: Long): Deploy =
    sourceDeploy(ByteString.copyFromUtf8(source), timestamp, gasLimit)

  def sourceDeploy(sessionCode: ByteString, timestamp: Long, gasLimit: Long): Deploy =
    basicDeploy(timestamp, sessionCode)

  // https://casperlabs.atlassian.net/browse/EE-283
  // We are hardcoding exchange rate for DEV NET at 10:1
  // (1 token buys you 10 units of gas).
  // Later, post DEV NET, conversion rate will be part of a deploy.
  val GAS_PRICE = 10
  val GAS_LIMIT = 100000000L

  def deployDataToEEDeploy(d: Deploy): ipc.Deploy = ipc.Deploy(
    address = d.getHeader.accountPublicKey,
    timestamp = d.getHeader.timestamp,
    session = d.getBody.session.map { case Deploy.Code(code, args) => ipc.DeployCode(code, args) },
    payment = d.getBody.payment.map { case Deploy.Code(code, args) => ipc.DeployCode(code, args) },
    // The new data type doesn't have a limit field. Remove this once payment is implemented.
    gasLimit =
      if (d.getBody.getPayment.code.isEmpty || d.getBody.getPayment == d.getBody.getSession) {
        sys.env.get("CL_DEFAULT_GAS_LIMIT").map(_.toLong).getOrElse(GAS_LIMIT)
      } else 0L,
    gasPrice = GAS_PRICE,
    nonce = d.getHeader.nonce
  )

  def dependenciesHashesOf(b: Block): List[BlockHash] = {
    val missingParents = parentHashes(b).toSet
    val missingJustifications = b.getHeader.justifications
      .map(_.latestBlockHash)
      .toSet
    (missingParents union missingJustifications).toList
  }

  implicit class DeployOps(d: Deploy) {
    def incrementNonce(): Deploy =
      this.withNonce(d.header.get.nonce + 1)

    def withNonce(newNonce: Long): Deploy =
      d.withHeader(d.header.get.withNonce(newNonce))
  }

  def randomAccountAddress(): ByteString =
    ByteString.copyFrom(scala.util.Random.nextString(32), "UTF-8")
}
