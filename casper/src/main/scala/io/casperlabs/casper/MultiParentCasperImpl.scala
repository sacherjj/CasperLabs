package io.casperlabs.casper

import cats.{Applicative, Monad}
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.util.TopologicalSortUtil
import io.casperlabs.blockstorage.{
  BlockDagRepresentation,
  BlockDagStorage,
  BlockMetadata,
  BlockStore
}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.comm.CommUtil
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.casper.util.rholang._
import io.casperlabs.catscontrib._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc.ExecutionEffect
import io.casperlabs.models.{InternalErrors, InternalProcessedDeploy}
import io.casperlabs.shared._
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicAny
import io.casperlabs.shared.AttemptOps._
import io.casperlabs.catscontrib.TaskContrib._

import scala.collection.immutable.HashSet
import scala.collection.mutable
import scala.concurrent.SyncVar
import scala.concurrent.duration.Duration

class MultiParentCasperImpl[F[_]: Sync: Capture: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: SafetyOracle: BlockStore: RPConfAsk: ToAbstractContext: BlockDagStorage](
    runtimeManager: RuntimeManager[Task],
    validatorId: Option[ValidatorIdentity],
    genesis: BlockMessage,
    postGenesisStateHash: StateHash,
    shardId: String
)(implicit scheduler: Scheduler)
    extends MultiParentCasper[F] {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  type BlockHash = ByteString
  type Validator = ByteString

  //TODO: Extract hardcoded version
  private val version = 1L

  private val emptyStateHash = runtimeManager.emptyStateHash

  private val blockBuffer: mutable.HashSet[BlockMessage] =
    new mutable.HashSet[BlockMessage]()
  private val blockBufferDependencyDagState =
    new AtomicMonadState[F, DoublyLinkedDag[BlockHash]](AtomicAny(BlockDependencyDag.empty))

  private val deployAndEffectsHist: mutable.HashMap[Deploy, ExecutionEffect] =
    new mutable.HashMap[Deploy, ExecutionEffect]()

  // Used to keep track of when other validators detect the equivocation consisting of the base block
  // at the sequence number identified by the (validator, base equivocation sequence number) pair of
  // each EquivocationRecord.
  private val equivocationsTracker: mutable.Set[EquivocationRecord] =
    new mutable.HashSet[EquivocationRecord]()
  private val invalidBlockTracker: mutable.HashSet[BlockHash] =
    new mutable.HashSet[BlockHash]()

  // TODO: Extract hardcoded fault tolerance threshold
  private val faultToleranceThreshold         = 0f
  private val lastFinalizedBlockHashContainer = Ref.unsafe[F, BlockHash](genesis.blockHash)

  private val processingBlock          = new SyncVar[Unit]()
  private val PROCESSING_BLOCK_TIMEOUT = 5 * 60 * 1000L
  processingBlock.put(())

  def addBlock(b: BlockMessage): F[BlockStatus] =
    for {
      _              <- Sync[F].delay(processingBlock.take(PROCESSING_BLOCK_TIMEOUT))
      dag            <- blockDag
      blockHash      = b.blockHash
      containsResult <- dag.contains(blockHash)
      result <- if (containsResult || blockBuffer.exists(
                      _.blockHash == blockHash
                    )) {
                 Log[F]
                   .info(
                     s"Block ${PrettyPrinter.buildString(b.blockHash)} has already been processed by another thread."
                   )
                   .map(_ => BlockStatus.processing)
               } else {
                 internalAddBlock(b)
               }
      _ <- Sync[F].delay(processingBlock.put(()))
    } yield result

  private def internalAddBlock(b: BlockMessage): F[BlockStatus] =
    for {
      validFormat  <- Validate.formatOfFields[F](b)
      validSig     <- Validate.blockSignature[F](b)
      dag          <- blockDag
      validSender  <- Validate.blockSender[F](b, genesis, dag)
      validVersion <- Validate.version[F](b, version)
      attempt <- if (!validFormat) InvalidUnslashableBlock.pure[F]
                else if (!validSig) InvalidUnslashableBlock.pure[F]
                else if (!validSender) InvalidUnslashableBlock.pure[F]
                else if (!validVersion) InvalidUnslashableBlock.pure[F]
                else attemptAdd(b)
      _ <- attempt match {
            case MissingBlocks => ().pure[F]
            case _ =>
              Sync[F].delay { blockBuffer -= b } *> blockBufferDependencyDagState.modify(
                blockBufferDependencyDag =>
                  DoublyLinkedDagOperations.remove(blockBufferDependencyDag, b.blockHash)
              )
          }
      _ <- attempt match {
            case MissingBlocks           => ().pure[F]
            case IgnorableEquivocation   => ().pure[F]
            case InvalidUnslashableBlock => ().pure[F]
            case _ =>
              reAttemptBuffer // reAttempt for any status that resulted in the adding of the block into the view
          }
      estimates                     <- estimator(dag)
      tip                           = estimates.head
      _                             <- Log[F].info(s"New fork-choice tip is block ${PrettyPrinter.buildString(tip.blockHash)}.")
      lastFinalizedBlockHash        <- lastFinalizedBlockHashContainer.get
      updatedLastFinalizedBlockHash <- updateLastFinalizedBlock(dag, lastFinalizedBlockHash)
      _                             <- lastFinalizedBlockHashContainer.set(updatedLastFinalizedBlockHash)
    } yield attempt

  private def updateLastFinalizedBlock(
      dag: BlockDagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[BlockHash] =
    for {
      childrenHashes <- dag
                         .children(lastFinalizedBlockHash)
                         .map(_.getOrElse(Set.empty[BlockHash]).toList)
      maybeFinalizedChild <- ListContrib.findM(
                              childrenHashes,
                              (blockHash: BlockHash) =>
                                isGreaterThanFaultToleranceThreshold(dag, blockHash)
                            )
      newFinalizedBlock <- maybeFinalizedChild match {
                            case Some(finalizedChild) =>
                              updateLastFinalizedBlock(dag, finalizedChild)
                            case None => lastFinalizedBlockHash.pure[F]
                          }
    } yield newFinalizedBlock

  private def isGreaterThanFaultToleranceThreshold(
      dag: BlockDagRepresentation[F],
      blockHash: BlockHash
  ): F[Boolean] =
    for {
      faultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, blockHash)
      _ <- Log[F].info(
            s"Fault tolerance for block ${PrettyPrinter.buildString(blockHash)} is $faultTolerance."
          )
    } yield faultTolerance > faultToleranceThreshold

  def contains(b: BlockMessage): F[Boolean] =
    BlockStore[F].contains(b.blockHash).map(_ || blockBuffer.contains(b))

  def addDeploy(deploy: Deploy, effects: ExecutionEffect): F[Unit] =
    for {
      _ <- Sync[F].delay {
            deployAndEffectsHist.put(deploy, effects)
          }
      _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(deploy)}")
    } yield ()

  def deploy(d: DeployData): F[Either[Throwable, Unit]] =
    ToAbstractContext[F]
      .fromTask(
        runtimeManager
          .sendDeploy(
            ProtoUtil.deployDataToEEDeploy(d)
          )
      )
      .flatMap {
        case Right(effects: ExecutionEffect) =>
          addDeploy(
            Deploy(
              sessionCode = d.sessionCode,
              raw = Some(d)
            ),
            effects
          ).as(Right(()))
        case Left(err) => new Throwable(s"Error in parsing term: \n$err").asLeft[Unit].pure[F]
      }

  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockMessage]] =
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
    case Some(vId @ ValidatorIdentity(publicKey, privateKey, sigAlgorithm)) =>
      for {
        dag              <- blockDag
        orderedHeads     <- estimator(dag)
        p                <- chooseNonConflicting[F](orderedHeads, genesis, dag)
        r                <- remDeploys(dag, p)
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
  private def remDeploys(
      dag: BlockDagRepresentation[F],
      p: Seq[BlockMessage]
  ): F[Seq[(Deploy, ExecutionEffect)]] =
    for {
      result <- Sync[F].delay { deployAndEffectsHist.clone() }
      _ <- DagOperations
            .bfTraverseF[F, BlockMessage](p.toList)(ProtoUtil.unsafeGetParents[F])
            .foreach(
              b =>
                Sync[F].delay {
                  b.body.foreach(_.deploys.flatMap(_.deploy).foreach(result.remove))
                }
            )
    } yield result.toSeq

  private def createProposal(
      dag: BlockDagRepresentation[F],
      p: Seq[BlockMessage],
      r: Seq[(Deploy, ExecutionEffect)],
      justifications: Seq[Justification]
  ): F[CreateBlockStatus] =
    for {
      now <- Time[F].currentMillis
      possibleProcessedDeploys <- InterpreterUtil.computeDeploysCheckpoint[F](
                                   p,
                                   r,
                                   dag,
                                   runtimeManager,
                                   Some(now)
                                 )
      result <- possibleProcessedDeploys match {
                 case Left(ex) =>
                   Log[F]
                     .error(
                       s"Critical error encountered while processing deploys: ${ex.getMessage}"
                     )
                     .map(_ => CreateBlockStatus.internalDeployError(ex))

                 case Right((preStateHash, postStateHash, processedDeploys)) =>
                   val (internalErrors, persistableDeploys) =
                     processedDeploys.partition(_.result.isInternalError)
                   internalErrors.toList
                     .traverse {
                       case InternalProcessedDeploy(deploy, _, InternalErrors(errors)) =>
                         val errorsMessage = errors.map(_.getMessage).mkString("\n")
                         Log[F].error(
                           s"Internal error encountered while processing deploy ${PrettyPrinter
                             .buildString(deploy)}: $errorsMessage"
                         )
                       case _ => ().pure[F]
                     }
                     .map(_ => {
                       val maxBlockNumber: Long =
                         p.foldLeft(-1L) {
                           case (acc, b) => math.max(acc, blockNumber(b))
                         }
                       val newBonds = ProtoUtil.bonds(p.head)
                       // TODO: bring back when global state includes the bonds
                       //val newBonds = runtimeManager.computeBonds(postStateHash)
                       val postState = RChainState()
                         .withPreStateHash(preStateHash)
                         .withPostStateHash(postStateHash)
                         .withBonds(newBonds)
                         .withBlockNumber(maxBlockNumber + 1)

                       val body = Body()
                         .withState(postState)
                         .withDeploys(persistableDeploys.map(ProcessedDeployUtil.fromInternal))
                       val header = blockHeader(body, p.map(_.blockHash), version, now)
                       val block  = unsignedBlockProto(body, header, justifications, shardId)
                       CreateBlockStatus.created(block)
                     })
                     .flatMap[CreateBlockStatus] {
                       case status @ Created(block) =>
                         val number     = block.body.get.state.get.blockNumber
                         val transforms = r.flatMap(_._2.transformMap)
                         val msgBody = transforms
                           .map(t => {
                             val k    = PrettyPrinter.buildString(t.key.get)
                             val tStr = PrettyPrinter.buildString(t.transform.get)
                             s"$k :: $tStr"
                           })
                           .mkString("\n")
                         Log[F]
                           .info(s"Block #$number created with effects:\n$msgBody")
                           .map(_ => status)
                       case other => other.pure[F]
                     }
               }
    } yield result

  def blockDag: F[BlockDagRepresentation[F]] =
    BlockDagStorage[F].getRepresentation

  def storageContents(hash: StateHash): F[String] = """""".pure[F]

  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    (equivocationsTracker
      .map(_.equivocator)
      .toSet
      .flatMap(weights.get)
      .sum
      .toFloat / weightMapTotal(weights))
      .pure[F]

  /*
   * TODO: Pass in blockDag. We should only call _blockDag.get at one location.
   * This would require returning the updated block DAG with the block status.
   *
   * We want to catch equivocations only after we confirm that the block completing
   * the equivocation is otherwise valid.
   */
  private def attemptAdd(b: BlockMessage): F[BlockStatus] =
    for {
      _                    <- Log[F].info(s"Attempting to add Block ${PrettyPrinter.buildString(b.blockHash)} to DAG.")
      dag                  <- blockDag
      postValidationStatus <- Validate.blockSummary[F](b, genesis, dag, shardId)
      postTransactionsCheckStatus <- postValidationStatus.traverse(
                                      _ =>
                                        Validate.transactions[F](
                                          b,
                                          dag,
                                          emptyStateHash,
                                          runtimeManager
                                        )
                                    )
      postBondsCacheStatus <- postTransactionsCheckStatus.joinRight.traverse(
                               _ => Validate.bondsCache[F](b, runtimeManager)
                             )
      postNeglectedInvalidBlockStatus <- postBondsCacheStatus.joinRight.traverse(
                                          _ =>
                                            Validate
                                              .neglectedInvalidBlock[F](
                                                b,
                                                invalidBlockTracker.toSet
                                              )
                                        )
      postNeglectedEquivocationCheckStatus <- postNeglectedInvalidBlockStatus.joinRight
                                               .traverse(
                                                 _ =>
                                                   EquivocationDetector
                                                     .checkNeglectedEquivocationsWithUpdate[F](
                                                       equivocationsTracker,
                                                       b,
                                                       dag,
                                                       genesis
                                                     )
                                               )
      blockBufferDependencyDag <- blockBufferDependencyDagState.get
      postEquivocationCheckStatus <- postNeglectedEquivocationCheckStatus.joinRight.traverse(
                                      _ =>
                                        EquivocationDetector
                                          .checkEquivocations[F](blockBufferDependencyDag, b, dag)
                                    )
      status = postEquivocationCheckStatus.joinRight.merge
      _      <- addEffects(status, b)
    } yield status

  // TODO: Handle slashing
  private def addEffects(status: BlockStatus, block: BlockMessage): F[Unit] =
    status match {
      //Add successful! Send block to peers, log success, try to add other blocks
      case Valid =>
        addToState(block) *> CommUtil.sendBlock[F](block) *> Log[F].info(
          s"Added ${PrettyPrinter.buildString(block.blockHash)}"
        )
      case MissingBlocks =>
        Sync[F].delay { blockBuffer += block } *> fetchMissingDependencies(block)
      case AdmissibleEquivocation =>
        Sync[F].delay {
          val baseEquivocationBlockSeqNum = block.seqNum - 1
          if (equivocationsTracker.exists {
                case EquivocationRecord(validator, seqNum, _) =>
                  block.sender == validator && baseEquivocationBlockSeqNum == seqNum
              }) {
            // More than 2 equivocating children from base equivocation block and base block has already been recorded
          } else {
            val newEquivocationRecord =
              EquivocationRecord(block.sender, baseEquivocationBlockSeqNum, Set.empty[BlockHash])
            equivocationsTracker.add(newEquivocationRecord)
          }
        } *>
          addToState(block) *> CommUtil.sendBlock[F](block) *> Log[F].info(
          s"Added admissible equivocation child block ${PrettyPrinter.buildString(block.blockHash)}"
        )
      case IgnorableEquivocation =>
        /*
         * We don't have to include these blocks to the equivocation tracker because if any validator
         * will build off this side of the equivocation, we will get another attempt to add this block
         * through the admissible equivocations.
         */
        Log[F].info(
          s"Did not add block ${PrettyPrinter.buildString(block.blockHash)} as that would add an equivocation to the BlockDAG"
        )
      case InvalidUnslashableBlock =>
        handleInvalidBlockEffect(status, block)
      case InvalidFollows =>
        handleInvalidBlockEffect(status, block)
      case InvalidBlockNumber =>
        handleInvalidBlockEffect(status, block)
      case InvalidParents =>
        handleInvalidBlockEffect(status, block)
      case JustificationRegression =>
        handleInvalidBlockEffect(status, block)
      case InvalidSequenceNumber =>
        handleInvalidBlockEffect(status, block)
      case NeglectedInvalidBlock =>
        handleInvalidBlockEffect(status, block)
      case NeglectedEquivocation =>
        handleInvalidBlockEffect(status, block)
      case InvalidTransaction =>
        handleInvalidBlockEffect(status, block)
      case InvalidBondsCache =>
        handleInvalidBlockEffect(status, block)
      case InvalidRepeatDeploy =>
        handleInvalidBlockEffect(status, block)
      case InvalidShardId =>
        handleInvalidBlockEffect(status, block)
      case InvalidBlockHash =>
        handleInvalidBlockEffect(status, block)
      case InvalidDeployCount =>
        handleInvalidBlockEffect(status, block)
      case Processing =>
        throw new RuntimeException(s"A block should not be processing at this stage.")
      case BlockException(ex) =>
        Log[F].error(s"Encountered exception in while processing block ${PrettyPrinter
          .buildString(block.blockHash)}: ${ex.getMessage}")
    }

  private def fetchMissingDependencies(b: BlockMessage): F[Unit] =
    for {
      dag            <- blockDag
      missingParents = parentHashes(b).toSet
      missingJustifications = b.justifications
        .map(_.latestBlockHash)
        .toSet
      allDependencies = (missingParents union missingJustifications).toList
      missingDependencies <- allDependencies.filterA(
                              blockHash =>
                                dag
                                  .lookup(blockHash)
                                  .map(_.isEmpty)
                            )
      missingUnseenDependencies = missingDependencies.filterNot(
        blockHash => blockBuffer.exists(_.blockHash == blockHash)
      )
      _ <- missingDependencies.traverse(hash => handleMissingDependency(hash, b))
      _ <- missingUnseenDependencies.traverse(hash => requestMissingDependency(hash))
    } yield ()

  private def handleMissingDependency(hash: BlockHash, parentBlock: BlockMessage): F[Unit] =
    for {
      _ <- blockBufferDependencyDagState.modify(
            blockBufferDependencyDag =>
              DoublyLinkedDagOperations
                .add[BlockHash](blockBufferDependencyDag, hash, parentBlock.blockHash)
          )
      _ <- CommUtil.sendBlockRequest[F](BlockRequest(Base16.encode(hash.toByteArray), hash))
    } yield ()

  private def requestMissingDependency(hash: BlockHash) =
    CommUtil.sendBlockRequest[F](BlockRequest(Base16.encode(hash.toByteArray), hash))

  private def handleInvalidBlockEffect(status: BlockStatus, block: BlockMessage): F[Unit] =
    for {
      _ <- Log[F].warn(
            s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for ${status.toString}."
          )
      // TODO: Slash block for status except InvalidUnslashableBlock
      _ <- Sync[F].delay(invalidBlockTracker += block.blockHash) *> addToState(block)
    } yield ()

  private def addToState(block: BlockMessage): F[Unit] =
    for {
      _ <- BlockStore[F].put(block.blockHash, block)
      _ <- BlockDagStorage[F].insert(block)
    } yield ()

  private def reAttemptBuffer: F[Unit] =
    for {
      blockBufferDependencyDag <- blockBufferDependencyDagState.get
      dependencyFree           = blockBufferDependencyDag.dependencyFree
      dependencyFreeBlocks = blockBuffer
        .filter(block => dependencyFree.contains(block.blockHash))
        .toList
      attempts <- dependencyFreeBlocks.traverse { b =>
                   for {
                     status <- attemptAdd(b)
                   } yield (b, status)
                 }
      _ <- if (attempts.isEmpty) {
            ().pure[F]
          } else {
            for {
              _ <- removeAdded(blockBufferDependencyDag, attempts)
              _ <- reAttemptBuffer
            } yield ()
          }
    } yield ()

  private def removeAdded(
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
      attempts: List[(BlockMessage, BlockStatus)]
  ): F[Unit] =
    for {
      successfulAdds <- attempts
                         .filter {
                           case (_, status) => status.inDag
                         }
                         .pure[F]
      _ <- unsafeRemoveFromBlockBuffer(successfulAdds)
      _ <- removeFromBlockBufferDependencyDag(blockBufferDependencyDag, successfulAdds)
    } yield ()

  private def unsafeRemoveFromBlockBuffer(
      successfulAdds: List[(BlockMessage, BlockStatus)]
  ): F[Unit] =
    Sync[F]
      .delay {
        successfulAdds.map {
          blockBuffer -= _._1
        }
      }
      .as(Unit)

  private def removeFromBlockBufferDependencyDag(
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
      successfulAdds: List[(BlockMessage, BlockStatus)]
  ): F[Unit] =
    blockBufferDependencyDagState.set(
      successfulAdds.foldLeft(blockBufferDependencyDag) {
        case (acc, successfulAdd) =>
          DoublyLinkedDagOperations.remove(acc, successfulAdd._1.blockHash)
      }
    )

  def getRuntimeManager: F[Option[RuntimeManager[Task]]] = Applicative[F].pure(Some(runtimeManager))

  def fetchDependencies: F[Unit] =
    for {
      blockBufferDependencyDag <- blockBufferDependencyDagState.get
      _ <- blockBufferDependencyDag.dependencyFree.toList.traverse { hash =>
            CommUtil.sendBlockRequest[F](BlockRequest(Base16.encode(hash.toByteArray), hash))
          }
    } yield ()
}
