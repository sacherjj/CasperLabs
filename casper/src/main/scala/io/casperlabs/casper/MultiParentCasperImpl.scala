package io.casperlabs.casper

import cats.data.EitherT
import cats.effect.Sync
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockDagStorage, BlockStore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.Validate.ValidateErrorWrapper
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.comm.CommUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.execengine.{DeploysCheckpoint, ExecEngineUtil}
import io.casperlabs.catscontrib._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import io.casperlabs.ipc.ValidateRequest
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform

/**
  Encapsulates mutable state of the MultiParentCasperImpl

  @param seenBlockHashes - tracks hashes of all blocks seen so far
  @param blockBuffer
  @param deployBuffer
  @param invalidBlockTracker
  @param equivocationsTracker: Used to keep track of when other validators detect the equivocation consisting of the base block at the sequence number identified by the (validator, base equivocation sequence number) pair of each EquivocationRecord.
  */
final case class CasperState(
    seenBlockHashes: Set[BlockHash] = Set.empty[BlockHash],
    blockBuffer: Set[BlockMessage] = Set.empty[BlockMessage],
    deployBuffer: Set[DeployData] = Set.empty[DeployData],
    invalidBlockTracker: Set[BlockHash] = Set.empty[BlockHash],
    dependencyDag: DoublyLinkedDag[BlockHash] = BlockDependencyDag.empty,
    equivocationsTracker: Set[EquivocationRecord] = Set.empty[EquivocationRecord]
)

class MultiParentCasperImpl[F[_]: Sync: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: BlockStore: RPConfAsk: BlockDagStorage: ExecutionEngineService](
    validatorId: Option[ValidatorIdentity],
    genesis: BlockMessage,
    shardId: String,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f
)(implicit state: Cell[F, CasperState])
    extends MultiParentCasper[F] {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  type Validator = ByteString

  //TODO: Extract hardcoded version
  private val version = 1L

  private val lastFinalizedBlockHashContainer = Ref.unsafe[F, BlockHash](genesis.blockHash)

  /** Add a block if it hasn't been added yet. */
  def addBlock(
      block: BlockMessage,
      handleDoppelganger: (BlockMessage, Validator) => F[Unit]
  ): F[BlockStatus] =
    Sync[F].bracket(blockProcessingLock.acquire)(
      _ =>
        for {
          dag            <- blockDag
          blockHash      = block.blockHash
          containsResult <- dag.contains(blockHash)
          s              <- Cell[F, CasperState].read
          result <- if (containsResult || s.seenBlockHashes.contains(blockHash)) {
                     Log[F]
                       .info(
                         s"Block ${PrettyPrinter.buildString(blockHash)} has already been processed by another thread."
                       )
                       .map(_ => BlockStatus.processing)
                   } else {
                     (validatorId match {
                       case Some(ValidatorIdentity(publicKey, _, _)) =>
                         val sender = ByteString.copyFrom(publicKey)
                         handleDoppelganger(block, sender)
                       case None => ().pure[F]
                     }) *> Cell[F, CasperState].modify { s =>
                       s.copy(seenBlockHashes = s.seenBlockHashes + blockHash)
                     } *> internalAddBlock(block, dag)
                   }
        } yield result
    )(_ => blockProcessingLock.release)

  /** Validate the block, try to execute and store it,
    * execute any other block that depended on it,
    * update the finalized block reference. */
  private def internalAddBlock(
      block: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[BlockStatus] =
    for {
      validFormat            <- Validate.formatOfFields[F](block)
      validSig               <- Validate.blockSignature[F](block)
      validSender            <- Validate.blockSender[F](block, genesis, dag)
      validVersion           <- Validate.version[F](block, version)
      lastFinalizedBlockHash <- lastFinalizedBlockHashContainer.get
      attemptResult <- if (!validFormat) (InvalidUnslashableBlock, dag).pure[F]
                      else if (!validSig) (InvalidUnslashableBlock, dag).pure[F]
                      else if (!validSender) (InvalidUnslashableBlock, dag).pure[F]
                      else if (!validVersion) (InvalidUnslashableBlock, dag).pure[F]
                      else attemptAdd(block, dag, lastFinalizedBlockHash)
      (attempt, updatedDag) = attemptResult
      _                     <- removeAdded(List(block.blockHash -> attempt))
      _ <- attempt match {
            case MissingBlocks           => ().pure[F]
            case IgnorableEquivocation   => ().pure[F]
            case InvalidUnslashableBlock => ().pure[F]
            case _ =>
              reAttemptBuffer(updatedDag, lastFinalizedBlockHash) // reAttempt for any status that resulted in the adding of the block into the view
          }
      tipHashes <- estimator(updatedDag)
      _ <- Log[F].debug(
            s"Tip estimates: ${tipHashes.map(PrettyPrinter.buildString).mkString(", ")}"
          )
      tipHash                       = tipHashes.head
      _                             <- Log[F].info(s"New fork-choice tip is block ${PrettyPrinter.buildString(tipHash)}.")
      lastFinalizedBlockHash        <- lastFinalizedBlockHashContainer.get
      updatedLastFinalizedBlockHash <- updateLastFinalizedBlock(updatedDag, lastFinalizedBlockHash)
      _                             <- lastFinalizedBlockHashContainer.set(updatedLastFinalizedBlockHash)
      _ <- Log[F].info(
            s"New last finalized block hash is ${PrettyPrinter.buildString(updatedLastFinalizedBlockHash)}."
          )
    } yield attempt

  /** Go from the last finalized block and visit all children that can be finalized now.
    * Remove all of the deploys that are in any of them as they won't have to be attempted again. */
  private def updateLastFinalizedBlock(
      dag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[BlockHash] =
    for {
      childrenHashes <- dag
                         .children(lastFinalizedBlockHash)
                         .map(_.getOrElse(Set.empty[BlockHash]).toList)
      // Find all finalized children so that we can get rid of their deploys.
      finalizedChildren <- ListContrib.filterM(
                            childrenHashes,
                            (blockHash: BlockHash) =>
                              isGreaterThanFaultToleranceThreshold(dag, blockHash)
                          )
      newFinalizedBlock <- if (finalizedChildren.isEmpty) {
                            lastFinalizedBlockHash.pure[F]
                          } else {
                            finalizedChildren.traverse { childHash =>
                              for {
                                removed <- removeDeploysInBlock(childHash)
                                _ <- Log[F].info(
                                      s"Removed $removed deploys from deploy history as we finalized block ${PrettyPrinter
                                        .buildString(childHash)}."
                                    )
                                finalizedHash <- updateLastFinalizedBlock(dag, childHash)
                              } yield finalizedHash
                            } map (_.head)
                          }
    } yield newFinalizedBlock

  /** Remove deploys from the history which are included in a just finalised block. */
  private def removeDeploysInBlock(blockHash: BlockHash): F[Int] =
    for {
      block              <- ProtoUtil.unsafeGetBlock[F](blockHash)
      deploysToRemove    = block.body.get.deploys.map(_.deploy.get).toSet
      stateBefore        <- Cell[F, CasperState].read
      initialHistorySize = stateBefore.deployBuffer.size
      _ <- Cell[F, CasperState].modify { s =>
            s.copy(deployBuffer = s.deployBuffer.filterNot(deploysToRemove))
          }
      stateAfter     <- Cell[F, CasperState].read
      deploysRemoved = initialHistorySize - stateAfter.deployBuffer.size
    } yield deploysRemoved

  /*
   * On the first pass, block B is finalized if B's main parent block is finalized
   * and the safety oracle says B's normalized fault tolerance is above the threshold.
   * On the second pass, block B is finalized if any of B's children blocks are finalized.
   *
   * TODO: Implement the second pass in BlockAPI
   */
  private def isGreaterThanFaultToleranceThreshold(
      dag: BlockDagRepresentation[F],
      blockHash: BlockHash
  ): F[Boolean] =
    for {
      faultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, blockHash)
      _ <- Log[F].info(
            s"Fault tolerance for block ${PrettyPrinter.buildString(blockHash)} is $faultTolerance; threshold is $faultToleranceThreshold"
          )
    } yield faultTolerance > faultToleranceThreshold

  /** Check that either we have the block already scheduled but missing dependencies, or it's in the store */
  def contains(
      block: BlockMessage
  ): F[Boolean] =
    Cell[F, CasperState].read
      .map(_.blockBuffer.exists(_.blockHash == block.blockHash))
      .ifM(
        true.pure[F],
        BlockStore[F].contains(block.blockHash)
      )

  /** Add a deploy to the buffer, if the code passes basic validation. */
  def deploy(deployData: DeployData): F[Either[Throwable, Unit]] =
    (deployData.session, deployData.payment) match {
      //TODO: verify sig immediately (again, so we fail fast)
      case (Some(session), Some(payment)) =>
        val req = ExecutionEngineService[F].verifyWasm(ValidateRequest(session.code, payment.code))

        EitherT(req)
          .leftMap(c => new IllegalArgumentException(s"Contract verification failed: $c"))
          .flatMapF(_ => addDeploy(deployData) map (_.asRight[Throwable]))
          .value
      // TODO: Genesis doesn't have payment code; does it come here?
      case (None, _) | (_, None) =>
        Either
          .left[Throwable, Unit](
            // TODO: Use IllegalArgument from comms.
            new IllegalArgumentException(s"Deploy was missing session and/or payment code.")
          )
          .pure[F]
    }

  /** Add a deploy to the buvfer, to be executed later. */
  private def addDeploy(deployData: DeployData): F[Unit] =
    Cell[F, CasperState].modify { s =>
      s.copy(deployBuffer = s.deployBuffer + deployData)
    } *> Log[F].info(s"Received ${PrettyPrinter.buildString(deployData)}")

  /** Return the list of tips. */
  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockHash]] =
    for {
      lastFinalizedBlockHash <- lastFinalizedBlockHashContainer.get
      rankedEstimates        <- Estimator.tips[F](dag, lastFinalizedBlockHash)
    } yield rankedEstimates

  /*
   * Logic:
   *  -Score each of the blockDAG heads extracted from the block messages via GHOST
   *  -Let P = subset of heads such that P contains no conflicts and the total score is maximized
   *  -Let R = subset of deploy messages which are not included in DAG obtained by following blocks in P
   *  -If R is non-empty then create a new block with parents equal to P and (non-conflicting) txns obtained from R
   *  -Else if R is empty and |P| > 1 then create a block with parents equal to P and no transactions
   *  -Else None
   *
   *  TODO: Make this return Either so that we get more information about why not block was
   *  produced (no deploys, already processing, no validator id)
   */
  def createBlock: F[CreateBlockStatus] = validatorId match {
    case Some(ValidatorIdentity(publicKey, privateKey, sigAlgorithm)) =>
      for {
        dag       <- blockDag
        tipHashes <- estimator(dag)
        p         <- chooseNonConflicting[F](tipHashes, genesis, dag)
        _ <- Log[F].info(
              s"${p.size} parents out of ${tipHashes.size} latest blocks will be used."
            )
        r                <- remainingDeploys(dag, p)
        bondedValidators = bonds(p.head).map(_.validator).toSet
        //We ensure that only the justifications given in the block are those
        //which are bonded validators in the chosen parent. This is safe because
        //any latest message not from a bonded validator will not change the
        //final fork-choice.
        latestMessages <- dag.latestMessages
        justifications = toJustification(latestMessages)
          .filter(j => bondedValidators.contains(j.validator))
        proposal <- if (r.nonEmpty || p.length > 1) {
                     createProposal(dag, p, r, justifications)
                   } else {
                     CreateBlockStatus.noNewDeploys.pure[F]
                   }
        signedBlock <- proposal match {
                        case Created(blockMessage) =>
                          signBlock(blockMessage, dag, publicKey, privateKey, sigAlgorithm, shardId)
                            .map(Created.apply)
                        case _ => proposal.pure[F]
                      }
      } yield signedBlock
    case None => CreateBlockStatus.readOnlyMode.pure[F]
  }

  def lastFinalizedBlock: F[BlockMessage] =
    for {
      lastFinalizedBlockHash <- lastFinalizedBlockHashContainer.get
      blockMessage           <- ProtoUtil.unsafeGetBlock[F](lastFinalizedBlockHash)
    } yield blockMessage

  // TODO: Optimize for large number of deploys accumulated over history
  /** Get the deploys that are not present in the past of the chosen parents. */
  private def remainingDeploys(
      dag: BlockDagRepresentation[F],
      parents: Seq[BlockMessage]
  ): F[Seq[DeployData]] =
    for {
      state <- Cell[F, CasperState].read
      hist  = state.deployBuffer
      // TODO: Quit traversing when the buffer becomes empty.
      d <- DagOperations
            .bfTraverseF[F, BlockMessage](parents.toList)(ProtoUtil.unsafeGetParents[F])
            .map { b =>
              b.body
                .map(_.deploys.flatMap(_.deploy))
                .toSeq
                .flatten
            }
            .toList
      deploy = d.flatten.toSet
      result = (hist.diff(deploy)).toSeq
    } yield result

  //TODO: Need to specify SEQ vs PAR type block?
  /** Execute a set of deploys in the context of chosen parents. Compile them into a block if everything goes fine. */
  private def createProposal(
      dag: BlockDagRepresentation[F],
      parents: Seq[BlockMessage],
      deploys: Seq[DeployData],
      justifications: Seq[Justification]
  ): F[CreateBlockStatus] =
    (for {
      now <- Time[F].currentMillis
      s   <- Cell[F, CasperState].read
      stateResult <- ExecEngineUtil
                      .computeDeploysCheckpoint(parents, deploys, dag)
      DeploysCheckpoint(preStateHash, postStateHash, deploysForBlock, number) = stateResult
      //TODO: compute bonds properly
      newBonds = ProtoUtil.bonds(parents.head)
      postState = RChainState()
        .withPreStateHash(preStateHash)
        .withPostStateHash(postStateHash)
        .withBonds(newBonds)
        .withBlockNumber(number)

      body = Body()
        .withState(postState)
        .withDeploys(deploysForBlock)
      header = blockHeader(body, parents.map(_.blockHash), version, now)
      block  = unsignedBlockProto(body, header, justifications, shardId)
    } yield CreateBlockStatus.created(block)).handleErrorWith(
      ex =>
        Log[F]
          .error(
            s"Critical error encountered while processing deploys: ${ex.getMessage}"
          )
          .map(_ => CreateBlockStatus.internalDeployError(ex))
    )

  // MultiParentCasper Exposes the block DAG to those who need it.
  def blockDag: F[BlockDagRepresentation[F]] =
    BlockDagStorage[F].getRepresentation

  // RChain used ot return the whole database as a String for testing.
  def storageContents(hash: StateHash): F[String] =
    """""".pure[F]

  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    for {
      state   <- Cell[F, CasperState].read
      tracker = state.equivocationsTracker
    } yield
      (tracker
        .map(_.equivocator)
        .flatMap(weights.get)
        .sum
        .toFloat / weightMapTotal(weights))

  implicit val functorRaiseInvalidBlock = Validate.raiseValidateErrorThroughSync[F]

  /* Execute the block to get the effects then do some more validation.
   * Save the effects if everything checks out.
   * We want to catch equivocations only after we confirm that the block completing
   * the equivocation is otherwise valid. */
  private def attemptAdd(
      b: BlockMessage,
      dag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[(BlockStatus, BlockDagRepresentation[F])] = {
    val validationStatus = (for {
      _ <- Log[F].info(s"Attempting to add Block ${PrettyPrinter.buildString(b.blockHash)} to DAG.")
      postValidationStatus <- Validate
                               .blockSummary[F](b, genesis, dag, shardId, lastFinalizedBlockHash)
      s <- Cell[F, CasperState].read
      processedHash <- ExecEngineUtil
                        .effectsForBlock(b, dag)
                        .recoverWith {
                          case _ => FunctorRaise[F, InvalidBlock].raise(InvalidTransaction)
                        }
      (preStateHash, blockEffects) = processedHash
      _ <- Validate.transactions[F](
            b,
            dag,
            preStateHash,
            blockEffects
          )
      _ <- Validate.bondsCache[F](b, ProtoUtil.bonds(genesis))
      _ <- Validate
            .neglectedInvalidBlock[F](
              b,
              s.invalidBlockTracker
            )
      _ <- EquivocationDetector
            .checkNeglectedEquivocationsWithUpdate[F](
              b,
              dag,
              genesis
            )
      _ <- EquivocationDetector.checkEquivocations[F](s.dependencyDag, b, dag)
    } yield blockEffects).attempt

    validationStatus.flatMap {
      case Right(effects) =>
        addEffects(Valid, b, effects, dag).tupleLeft(Valid)
      case Left(ValidateErrorWrapper(invalid)) =>
        addEffects(invalid, b, Seq.empty, dag)
          .tupleLeft(invalid)
      case Left(unexpected) =>
        for {
          _ <- Log[F].error(
                s"Unexpected exception during validation of the block ${Base16.encode(b.blockHash.toByteArray)}",
                unexpected
              )
          _ <- Sync[F].raiseError[BlockStatus](unexpected)
        } yield (BlockException(unexpected), dag)
    }
  }

  // TODO: Handle slashing
  /** Either store the block with its transformation and gossip it,
    * or add it to the buffer in case the dependencies are missing. */
  private def addEffects(
      status: BlockStatus,
      block: BlockMessage,
      transforms: Seq[ipc.TransformEntry],
      dag: BlockDagRepresentation[F]
  ): F[BlockDagRepresentation[F]] =
    status match {
      //Add successful! Send block to peers, log success, try to add other blocks
      case Valid =>
        for {
          updatedDag <- addToState(block, transforms)
          _          <- CommUtil.sendBlock[F](block)
          _ <- Log[F].info(
                s"Added ${PrettyPrinter.buildString(block.blockHash)}"
              )
        } yield updatedDag
      case MissingBlocks =>
        Cell[F, CasperState].modify { s =>
          s.copy(blockBuffer = s.blockBuffer + block)
        } *> fetchMissingDependencies(block) *> dag.pure[F]

      case AdmissibleEquivocation =>
        val baseEquivocationBlockSeqNum = block.seqNum - 1
        for {
          _ <- Cell[F, CasperState].modify { s =>
                if (s.equivocationsTracker.exists {
                      case EquivocationRecord(validator, seqNum, _) =>
                        block.sender == validator && baseEquivocationBlockSeqNum == seqNum
                    }) {
                  // More than 2 equivocating children from base equivocation block and base block has already been recorded
                  s
                } else {
                  val newEquivocationRecord =
                    EquivocationRecord(
                      block.sender,
                      baseEquivocationBlockSeqNum,
                      Set.empty[BlockHash]
                    )
                  s.copy(equivocationsTracker = s.equivocationsTracker + newEquivocationRecord)
                }
              }
          updatedDag <- addToState(block, transforms)
          _          <- CommUtil.sendBlock[F](block)
          _ <- Log[F].info(
                s"Added admissible equivocation child block ${PrettyPrinter.buildString(block.blockHash)}"
              )
        } yield updatedDag
      case IgnorableEquivocation =>
        /*
         * We don't have to include these blocks to the equivocation tracker because if any validator
         * will build off this side of the equivocation, we will get another attempt to add this block
         * through the admissible equivocations.
         */
        Log[F]
          .info(
            s"Did not add block ${PrettyPrinter.buildString(block.blockHash)} as that would add an equivocation to the BlockDAG"
          ) *> dag.pure[F]
      case InvalidUnslashableBlock =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidFollows =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidBlockNumber =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidParents =>
        handleInvalidBlockEffect(status, block, transforms)
      case JustificationRegression =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidSequenceNumber =>
        handleInvalidBlockEffect(status, block, transforms)
      case NeglectedInvalidBlock =>
        handleInvalidBlockEffect(status, block, transforms)
      case NeglectedEquivocation =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidTransaction =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidBondsCache =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidRepeatDeploy =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidShardId =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidBlockHash =>
        handleInvalidBlockEffect(status, block, transforms)
      case InvalidDeployCount =>
        handleInvalidBlockEffect(status, block, transforms)
      case Processing =>
        throw new RuntimeException(s"A block should not be processing at this stage.")
      case BlockException(ex) =>
        Log[F].error(s"Encountered exception in while processing block ${PrettyPrinter
          .buildString(block.blockHash)}: ${ex.getMessage}") *> dag.pure[F]
    }

  /** Check if the block has dependencies that we don't have in store of buffer.
    * Add those to the dependency DAG and ask peers to send it. */
  private def fetchMissingDependencies(
      block: BlockMessage
  ): F[Unit] =
    for {
      dag <- blockDag
      missingDependencies <- dependenciesHashesOf(block)
                              .filterA(
                                blockHash =>
                                  dag
                                    .lookup(blockHash)
                                    .map(_.isEmpty)
                              )
      state <- Cell[F, CasperState].read
      missingUnseenDependencies = missingDependencies.filter(
        blockHash => !state.seenBlockHashes.contains(blockHash)
      )
      _ <- missingDependencies.traverse(hash => addMissingDependency(hash, block.blockHash))
      _ <- missingUnseenDependencies.traverse(hash => requestMissingDependency(hash))
    } yield ()

  /** Keep track of a block depending on another one, so we know when we can start processing. */
  private def addMissingDependency(ancestorHash: BlockHash, childHash: BlockHash): F[Unit] =
    Cell[F, CasperState].modify(
      s =>
        s.copy(
          dependencyDag = DoublyLinkedDagOperations
            .add[BlockHash](s.dependencyDag, ancestorHash, childHash)
        )
    )

  /** Ask all peers to send us a block. */
  private def requestMissingDependency(hash: BlockHash) =
    CommUtil.sendBlockRequest[F](BlockRequest(Base16.encode(hash.toByteArray), hash))

  /** Remember a block as being invalid, then save it to storage. */
  private def handleInvalidBlockEffect(
      status: BlockStatus,
      block: BlockMessage,
      effects: Seq[ipc.TransformEntry]
  ): F[BlockDagRepresentation[F]] =
    for {
      _ <- Log[F].warn(
            s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for ${status.toString}."
          )
      // TODO: Slash block for status except InvalidUnslashableBlock
      _ <- Cell[F, CasperState].modify { s =>
            s.copy(invalidBlockTracker = s.invalidBlockTracker + block.blockHash)
          }
      updateDag <- addToState(block, effects)
    } yield updateDag

  /** Save the block to the block and DAG storage. */
  private def addToState(
      block: BlockMessage,
      effects: Seq[ipc.TransformEntry]
  ): F[BlockDagRepresentation[F]] =
    for {
      _          <- BlockStore[F].put(block.blockHash, BlockMsgWithTransform(Some(block), effects))
      updatedDag <- BlockDagStorage[F].insert(block)
      hash       = block.blockHash
    } yield updatedDag

  /** After a block is executed we can try to execute the other blocks in the buffer that dependend on it. */
  private def reAttemptBuffer(
      dag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[List[(BlockHash, BlockStatus)]] =
    for {
      state          <- Cell[F, CasperState].read
      dependencyFree = state.dependencyDag.dependencyFree
      dependencyFreeBlocks = state.blockBuffer
        .filter(block => dependencyFree.contains(block.blockHash))
        .toList
      dependencyFreeAttempts <- dependencyFreeBlocks.foldM(
                                 (
                                   List.empty[(BlockHash, BlockStatus)],
                                   dag
                                 )
                               ) {
                                 case ((attempts, updatedDag), block) =>
                                   for {
                                     attempt <- attemptAdd(
                                                 block,
                                                 updatedDag,
                                                 lastFinalizedBlockHash
                                               )
                                     (status, updatedDag) = attempt
                                   } yield ((block.blockHash, status) :: attempts, updatedDag)
                               }
      (attempts, updatedDag) = dependencyFreeAttempts
      furtherAttempts <- if (attempts.isEmpty) List.empty.pure[F]
                        else {
                          removeAdded(attempts) *>
                            reAttemptBuffer(updatedDag, lastFinalizedBlockHash)
                        }
    } yield attempts ++ furtherAttempts

  /** Remove a all the bloks that were successfully added from the block buffer and the dependency DAG. */
  private def removeAdded(
      attempts: List[(BlockHash, BlockStatus)]
  ): F[Unit] = {
    val addedBlockHashes = attempts.collect {
      case (blockHash, status) if status.inDag => blockHash
    }.toSet

    Cell[F, CasperState].modify { s =>
      s.copy(
        blockBuffer = s.blockBuffer.filterNot(addedBlockHashes contains _.blockHash),
        dependencyDag = addedBlockHashes.foldLeft(s.dependencyDag) {
          case (dag, blockHash) =>
            DoublyLinkedDagOperations.remove(dag, blockHash)
        }
      )
    }
  }

  /** Called periodically from outside to ask all peers again
    * to send us blocks for which we are missing some dependencies. */
  def fetchDependencies: F[Unit] =
    for {
      s <- Cell[F, CasperState].read
      _ <- s.dependencyDag.dependencyFree.toList.traverse { hash =>
            CommUtil.sendBlockRequest[F](BlockRequest(Base16.encode(hash.toByteArray), hash))
          }
    } yield ()
}

object MultiParentCasperImpl {

  /** Component purely to validate, execute and store blocks.
    * Even the Genesis, to create it in the first place. */
  class StatelessExecutor[F[_]] {}
}
