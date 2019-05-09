package io.casperlabs.casper.util

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, Monad}
import com.google.protobuf.{ByteString, Int32Value, StringValue}
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.EquivocationRecord.SequenceNumber
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.protocol.{DeployData, _}
import io.casperlabs.casper.util.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.catscontrib.ski.id
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.ipc
import io.casperlabs.models.BlockMetadata
import io.casperlabs.shared.{Log, Time}

import scala.collection.immutable
import scala.util.control.NonFatal

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
      estimate: BlockMessage,
      acc: IndexedSeq[BlockMessage],
      depth: Int
  ): F[IndexedSeq[BlockMessage]] = {
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

  def unsafeGetBlock[F[_]: MonadThrowable: BlockStore](hash: BlockHash): F[BlockMessage] =
    for {
      maybeBlock <- BlockStore[F].getBlockMessage(hash)
      block <- maybeBlock match {
                case Some(b) => b.pure[F]
                case None =>
                  MonadThrowable[F].raiseError(
                    new Exception(s"BlockStore is missing hash ${PrettyPrinter.buildString(hash)}")
                  )
              }
    } yield block

  def creatorJustification(block: BlockMessage): Option[Justification] =
    block.justifications
      .find {
        case Justification(validator: Validator, _) =>
          validator == block.sender
      }

  def findCreatorJustificationAncestorWithSeqNum[F[_]: Monad: BlockStore](
      b: BlockMessage,
      seqNum: SequenceNumber
  ): F[Option[BlockMessage]] =
    if (b.seqNum == seqNum) {
      Option[BlockMessage](b).pure[F]
    } else {
      DagOperations
        .bfTraverseF(List(b)) { block =>
          getCreatorJustificationAsList[F](block, block.sender)
        }
        .find(_.seqNum == seqNum)
    }

  // TODO: Replace with getCreatorJustificationAsListUntilGoal
  def getCreatorJustificationAsList[F[_]: Monad: BlockStore](
      block: BlockMessage,
      validator: Validator,
      goalFunc: BlockMessage => Boolean = _ => false
  ): F[List[BlockMessage]] = {
    val maybeCreatorJustificationHash =
      block.justifications.find(_.validator == validator)
    maybeCreatorJustificationHash match {
      case Some(creatorJustificationHash) =>
        for {
          maybeCreatorJustification <- BlockStore[F].getBlockMessage(
                                        creatorJustificationHash.latestBlockHash
                                      )
          maybeCreatorJustificationAsList = maybeCreatorJustification match {
            case Some(creatorJustification) =>
              if (goalFunc(creatorJustification)) {
                List.empty[BlockMessage]
              } else {
                List(creatorJustification)
              }
            case None =>
              List.empty[BlockMessage]
          }
        } yield maybeCreatorJustificationAsList
      case None => List.empty[BlockMessage].pure[F]
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
      block <- OptionT(blockDag.lookup(blockHash))
      creatorJustificationHash <- OptionT.fromOption[F](
                                   block.justifications
                                     .find(_.validator == block.sender)
                                     .map(_.latestBlockHash)
                                 )
      creatorJustification <- OptionT(blockDag.lookup(creatorJustificationHash))
      creatorJustificationAsList = if (goalFunc(creatorJustification.blockHash)) {
        List.empty[BlockHash]
      } else {
        List(creatorJustification.blockHash)
      }
    } yield creatorJustificationAsList).fold(List.empty[BlockHash])(id)

  def weightMap(blockMessage: BlockMessage): Map[ByteString, Long] =
    blockMessage.body match {
      case Some(block) =>
        block.state match {
          case Some(state) => weightMap(state)
          case None        => Map.empty[ByteString, Long]
        }
      case None => Map.empty[ByteString, Long]
    }

  private def weightMap(state: RChainState): Map[ByteString, Long] =
    state.bonds.map {
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

  def mainParent[F[_]: Monad: BlockStore](blockMessage: BlockMessage): F[Option[BlockMessage]] = {
    val maybeParentHash = for {
      hdr        <- blockMessage.header
      parentHash <- hdr.parentsHashList.headOption
    } yield parentHash
    maybeParentHash match {
      case Some(parentHash) => BlockStore[F].getBlockMessage(parentHash)
      case None             => none[BlockMessage].pure[F]
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
      b: BlockMessage,
      validator: ByteString
  ): F[Long] =
    for {
      maybeMainParent <- mainParent[F](b)
      weightFromValidator = maybeMainParent
        .map(weightMap(_).getOrElse(validator, 0L))
        .getOrElse(weightMap(b).getOrElse(validator, 0L)) //no parents means genesis -- use itself
    } yield weightFromValidator

  def weightFromSender[F[_]: Monad: BlockStore](b: BlockMessage): F[Long] =
    weightFromValidator[F](b, b.sender)

  def parentHashes(b: BlockMessage): Seq[ByteString] =
    b.header.fold(Seq.empty[ByteString])(_.parentsHashList)

  def unsafeGetParents[F[_]: MonadThrowable: BlockStore](b: BlockMessage): F[List[BlockMessage]] =
    ProtoUtil.parentHashes(b).toList.traverse { parentHash =>
      ProtoUtil.unsafeGetBlock[F](parentHash)
    }

  def containsDeploy(b: BlockMessage, user: ByteString, timestamp: Long): Boolean =
    deploys(b).toStream
      .flatMap(_.deploy)
      .exists(deployData => deployData.user == user && deployData.timestamp == timestamp)

  def deploys(b: BlockMessage): Seq[ProcessedDeploy] =
    b.body.fold(Seq.empty[ProcessedDeploy])(_.deploys)

  def tuplespace(b: BlockMessage): Option[ByteString] =
    for {
      bd <- b.body
      ps <- bd.state
    } yield ps.postStateHash

  // TODO: Reconcile with def tuplespace above
  def postStateHash(b: BlockMessage): ByteString =
    b.getBody.getState.postStateHash

  def preStateHash(b: BlockMessage): ByteString =
    b.getBody.getState.preStateHash

  def bonds(b: BlockMessage): Seq[Bond] =
    (for {
      bd <- b.body
      ps <- bd.state
    } yield ps.bonds).getOrElse(List.empty[Bond])

  def blockNumber(b: BlockMessage): Long =
    (for {
      bd <- b.body
      ps <- bd.state
    } yield ps.blockNumber).getOrElse(0L)

  /*
   * Two blocks conflict if they both use the same deploy in different histories
   *
   * TODO: Update the logic of this function to make use of the trace logs and
   * say that two blocks don't conflict if they act on disjoint sets of channels
   */
  def conflicts[F[_]: MonadThrowable: BlockStore: Log](
      b1: BlockMessage,
      b2: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Boolean] =
    for {
      gca <- DagOperations.greatestCommonAncestorF[F](b1, b2, genesis, dag)
      result <- if (gca == b1 || gca == b2) {
                 //blocks which already exist in each other's chains do not conflict
                 false.pure[F]
               } else {
                 // Gather for each deploy which blocks it appears in.
                 def getDeploys(b: BlockMessage): F[Map[DeployData, Set[ByteString]]] =
                   for {
                     bAncestors <- DagOperations
                                    .bfTraverseF[F, BlockMessage](List(b))(
                                      ProtoUtil.unsafeGetParents[F]
                                    )
                                    .takeWhile(_ != gca)
                                    .toList
                     deploys = bAncestors
                       .flatMap { b =>
                         val deploys =
                           b.body.map(_.deploys.flatMap(_.deploy)).getOrElse(List.empty[DeployData])
                         deploys.map(_ -> b.blockHash)
                       }
                       .groupBy { case (d, _) => d }
                       .map { case (d, xs) => d -> xs.map(_._2).toSet }
                       .toMap
                   } yield deploys

                 for {
                   b1Deploys <- getDeploys(b1)
                   b2Deploys <- getDeploys(b2)
                   // Find deploys that appear in multiple blocks.
                   conflicts = (b1Deploys.keySet ++ b2Deploys.keySet).filter { d =>
                     val fromB1 = b1Deploys.getOrElse(d, Set.empty)
                     val fromB2 = b2Deploys.getOrElse(d, Set.empty)
                     (fromB1 union fromB2).size > 1
                   }
                   _ <- if (conflicts.nonEmpty) {
                         Log[F].debug(
                           s"Block ${PrettyPrinter.buildString(b1.blockHash)} conflicts ${PrettyPrinter
                             .buildString(b2.blockHash)} on ${conflicts.size} deploys."
                         )
                       } else ().pure[F]
                 } yield conflicts.nonEmpty
               }
    } yield result

  def chooseNonConflicting[F[_]: MonadThrowable: BlockStore: Log](
      blockHashes: Seq[BlockHash],
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[List[BlockMessage]] = {
    def nonConflicting(b: BlockMessage): BlockMessage => F[Boolean] =
      conflicts[F](_, b, genesis, dag).map(b => !b)

    (for {
      blocks <- blockHashes.toList.traverse(hash => ProtoUtil.unsafeGetBlock[F](hash))
      result <- blocks
                 .foldM(List.empty[BlockMessage]) {
                   case (acc, b) =>
                     Monad[F].ifM(acc.forallM(nonConflicting(b)))(
                       (b :: acc).pure[F],
                       acc.pure[F]
                     )
                 }
                 .map(_.reverse)
    } yield result) onError {
      case NonFatal(ex) =>
        Log[F].error(
          s"Failed to choose non-conflicting parents from ${blockHashes
            .map(PrettyPrinter.buildString(_))} (genesis is ${PrettyPrinter
            .buildString(genesis.blockHash)}): $ex",
          ex
        )
    }
  }

  def toJustification(
      latestMessages: collection.Map[Validator, BlockMetadata]
  ): Seq[Justification] =
    latestMessages.toSeq.map {
      case (validator, blockMetadata) =>
        Justification()
          .withValidator(validator)
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

  def protoHash[A <: { def toByteArray: Array[Byte] }](protoSeq: A*): ByteString =
    protoSeqHash(protoSeq)

  def protoSeqHash[A <: { def toByteArray: Array[Byte] }](protoSeq: Seq[A]): ByteString =
    hashByteArrays(protoSeq.map(_.toByteArray): _*)

  def hashByteArrays(items: Array[Byte]*): ByteString =
    ByteString.copyFrom(Blake2b256.hash(Array.concat(items: _*)))

  def blockHeader(
      body: Body,
      parentHashes: Seq[ByteString],
      protocolVersion: Long,
      timestamp: Long
  ): Header =
    Header()
      .withParentsHashList(parentHashes)
      .withPostStateHash(protoHash(body.state.get))
      .withDeploysHash(protoSeqHash(body.deploys))
      .withDeployCount(body.deploys.size)
      .withProtocolVersion(protocolVersion)
      .withTimestamp(timestamp)

  def unsignedBlockProto(
      body: Body,
      header: Header,
      justifications: Seq[Justification],
      shardId: String
  ): BlockMessage = {
    val hash = hashUnsignedBlock(header, justifications)

    BlockMessage()
      .withBlockHash(hash)
      .withHeader(header)
      .withBody(body)
      .withJustifications(justifications)
      .withShardId(shardId)
  }

  // TODO: Why isn't the shard ID part of this?
  def hashUnsignedBlock(header: Header, justifications: Seq[Justification]): BlockHash = {
    val items = header.toByteArray +: justifications.map(_.toByteArray)
    hashByteArrays(items: _*)
  }

  // TODO: Why isn't the justifications part of this?
  def hashSignedBlock(
      header: Header,
      sender: ByteString,
      sigAlgorithm: String,
      seqNum: Int,
      shardId: String,
      extraBytes: ByteString
  ): BlockHash =
    hashByteArrays(
      header.toByteArray,
      sender.toByteArray,
      StringValue.of(sigAlgorithm).toByteArray,
      Int32Value.of(seqNum).toByteArray,
      StringValue.of(shardId).toByteArray,
      extraBytes.toByteArray
    )

  def signBlock[F[_]: Applicative](
      block: BlockMessage,
      dag: BlockDagRepresentation[F],
      pk: Array[Byte],
      sk: Array[Byte],
      sigAlgorithm: String,
      shardId: String
  ): F[BlockMessage] = {

    val header = {
      //TODO refactor casper code to avoid the usage of Option fields in the block data structures
      // https://rchain.atlassian.net/browse/RHOL-572
      assert(block.header.isDefined, "A block without a header doesn't make sense")
      block.header.get
    }

    val sender = ByteString.copyFrom(pk)
    for {
      latestMessageOpt  <- dag.latestMessage(sender)
      seqNum            = latestMessageOpt.fold(0)(_.seqNum) + 1
      blockHash         = hashSignedBlock(header, sender, sigAlgorithm, seqNum, shardId, block.extraBytes)
      sigAlgorithmBlock = block.withSigAlgorithm(sigAlgorithm)
      sig               = ByteString.copyFrom(sigAlgorithmBlock.signFunction(blockHash.toByteArray, sk))
      signedBlock = sigAlgorithmBlock
        .withSender(sender)
        .withSig(sig)
        .withSeqNum(seqNum)
        .withBlockHash(blockHash)
        .withShardId(shardId)
    } yield signedBlock
  }

  def stringToByteString(string: String): ByteString =
    ByteString.copyFrom(Base16.decode(string))

  def basicDeployData[F[_]: Monad: Time](id: Int): F[DeployData] =
    Time[F].currentMillis.map(
      now =>
        DeployData()
          .withUser(ByteString.EMPTY)
          .withTimestamp(now)
          .withSession(DeployCode())
          .withPayment(DeployCode())
          .withGasLimit(Integer.MAX_VALUE)
    )

  def basicDeploy[F[_]: Monad: Time](id: Int): F[DeployData] =
    for {
      d <- basicDeployData[F](id)
    } yield d

  //Todo: it is for testing
  def basicProcessedDeploy[F[_]: Monad: Time](id: Int): F[ProcessedDeploy] =
    basicDeploy[F](id).map(deploy => ProcessedDeploy(deploy = Some(deploy)))

  def sourceDeploy(source: String, timestamp: Long, gasLimit: Long): DeployData =
    DeployData(
      user = ByteString.EMPTY,
      timestamp = timestamp,
      session = Some(DeployCode().withCode(ByteString.copyFromUtf8(source))),
      payment = Some(DeployCode()),
      gasLimit = gasLimit
    )

  def sourceDeploy(sessionCode: ByteString, timestamp: Long, gasLimit: Long): DeployData =
    DeployData(
      user = ByteString.EMPTY,
      timestamp = timestamp,
      session = Some(DeployCode().withCode(sessionCode)),
      payment = Some(DeployCode()),
      gasLimit = gasLimit
    )

  // https://casperlabs.atlassian.net/browse/EE-283
  // We are hardcoding exchange rate for DEV NET at 10:1
  // (1 token buys you 10 units of gas).
  // Later, post DEV NET, conversion rate will be part of a deploy.
  val GAS_PRICE = 10

  def deployDataToEEDeploy(dd: DeployData): ipc.Deploy = ipc.Deploy(
    address = dd.address,
    timestamp = dd.timestamp,
    session = dd.session.map { case DeployCode(code, args) => ipc.DeployCode(code, args) },
    payment = dd.payment.map { case DeployCode(code, args) => ipc.DeployCode(code, args) },
    gasLimit = dd.gasLimit,
    gasPrice = GAS_PRICE,
    nonce = dd.nonce
  )

  def dependenciesHashesOf(b: BlockMessage): List[BlockHash] = {
    val missingParents = parentHashes(b).toSet
    val missingJustifications = b.justifications
      .map(_.latestBlockHash)
      .toSet
    (missingParents union missingJustifications).toList
  }
}
