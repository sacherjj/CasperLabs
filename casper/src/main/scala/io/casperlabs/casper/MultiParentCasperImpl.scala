package io.casperlabs.casper

import cats.data.NonEmptyList
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import cats.mtl.FunctorRaise
import cats.Applicative
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.finality.MultiParentFinalizer.FinalizedBlocks
import io.casperlabs.casper.finality.MultiParentFinalizer
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.ProtocolVersions.Config
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.validation.Errors._
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits.fiberSyntax
import io.casperlabs.comm.gossiping
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.models.{Message, SmartContractEngineError}
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage, FinalityStorage}
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader, DeployStorageWriter}
import simulacrum.typeclass

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
  * Encapsulates mutable state of the MultiParentCasperImpl
  *
  * @param invalidBlockTracker
  */
final case class CasperState(
    invalidBlockTracker: Set[BlockHash] = Set.empty[BlockHash],
    dependencyDag: DoublyLinkedDag[BlockHash] = BlockDependencyDag.empty
)

@silent("is never used")
class MultiParentCasperImpl[F[_]: Concurrent: Log: Metrics: Time: BlockStorage: DagStorage: DeployBuffer: ExecutionEngineService: MultiParentFinalizer: DeployStorage: Validation: Fs2Compiler: DeploySelection: CasperLabsProtocol: EventEmitter](
    validatorSemaphoreMap: SemaphoreMap[F, ByteString],
    statelessExecutor: MultiParentCasperImpl.StatelessExecutor[F],
    validatorId: Option[ValidatorIdentity],
    genesis: Block,
    chainName: String,
    minTtl: FiniteDuration,
    upgrades: Seq[ipc.ChainSpec.UpgradePoint],
    lfbRef: Ref[F, BlockHash]
)(implicit state: Cell[F, CasperState])
    extends MultiParentCasper[F] {

  import MultiParentCasperImpl._

  implicit val logSource     = LogSource(this.getClass)
  implicit val metricsSource = CasperMetricsSource

  //TODO pull out
  implicit val functorRaiseInvalidBlock = validation.raiseValidateErrorThroughApplicativeError[F]

  type Validator = ByteString

  /** Add a block if it hasn't been added yet. */
  def addBlock(
      block: Block
  ): F[BlockStatus] = {
    def addBlock(
        validateAndAddBlock: (
            Option[StatelessExecutor.Context],
            Block
        ) => F[BlockStatus]
    ): F[BlockStatus] =
      validatorSemaphoreMap.withPermit(block.getHeader.validatorPublicKey) {
        for {
          dag       <- DagStorage[F].getRepresentation
          blockHash = block.blockHash
          inDag     <- dag.contains(blockHash)
          status <- if (inDag) {
                     Log[F]
                       .info(
                         s"Block ${PrettyPrinter.buildString(blockHash)} has already been processed by another thread."
                       ) *>
                       BlockStatus.processed.pure[F]
                   } else {
                     // This might be the first time we see this block, or it may not have been added to the state
                     // because it was an IgnorableEquivocation, but then we saw a child and now we got it again.
                     internalAddBlock(block, dag, validateAndAddBlock)
                   }
        } yield status
      }

    val handleInvalidTimestamp =
      (_: Option[StatelessExecutor.Context], block: Block) =>
        statelessExecutor
          .addEffects(InvalidUnslashableBlock, block, BlockEffects.empty)
          .as(InvalidUnslashableBlock: BlockStatus)

    // If the block timestamp is in the future, wait some time before adding it,
    // so we won't include it as a justification from the future.
    Validation.preTimestamp[F](block).attempt.flatMap {
      case Right(None) =>
        addBlock(statelessExecutor.validateAndAddBlock)
      case Right(Some(delay)) =>
        Log[F].info(
          s"${PrettyPrinter.buildString(block.blockHash) -> "block"} is ahead for $delay from now, will retry adding later"
        ) >>
          Time[F].sleep(delay) >>
          addBlock(statelessExecutor.validateAndAddBlock)
      case _ =>
        Log[F]
          .warn(
            s"${PrettyPrinter.buildString(block.blockHash) -> "block"} timestamp exceeded threshold"
          ) >>
          addBlock(handleInvalidTimestamp)
    }
  }

  /** Validate the block, try to execute and store it,
    * execute any other block that depended on it,
    * update the finalized block reference. */
  private def internalAddBlock(
      block: Block,
      dag: DagRepresentation[F],
      validateAndAddBlock: (
          Option[StatelessExecutor.Context],
          Block
      ) => F[BlockStatus]
  ): F[BlockStatus] = Metrics[F].timer("addBlock") {
    for {
      lastFinalizedBlockHash <- lfbRef.get
      message                <- MonadThrowable[F].fromTry(Message.fromBlock(block))
      status <- validateAndAddBlock(
                 StatelessExecutor.Context(genesis, lastFinalizedBlockHash).some,
                 block
               )
      _          <- removeAdded(List(block -> status), canRemove = _ != MissingBlocks)
      hashPrefix = PrettyPrinter.buildString(block.blockHash)
      // Update the last finalized block; remove finalized deploys from the buffer
      _ <- Log[F].debug(s"Updating last finalized block after adding ${hashPrefix -> "block"}")
      _ <- updateLastFinalizedBlock(message, dag).whenA(status == Valid)
      _ <- Log[F].debug(s"Finished adding ${hashPrefix -> "block"}")
    } yield status
  }

  /** Update the finalized block; return true if it changed. */
  private def updateLastFinalizedBlock(message: Message, dag: DagRepresentation[F]): F[Unit] =
    Metrics[F].timer("updateLastFinalizedBlock") {
      for {
        result <- MultiParentFinalizer[F].onNewMessageAdded(message)
        _ <- result.traverse {
              case fb @ FinalizedBlocks(mainParent, _, secondary) => {
                val mainParentFinalizedStr = PrettyPrinter.buildString(
                  mainParent
                )
                val secondaryParentsFinalizedStr =
                  secondary.map(PrettyPrinter.buildString).mkString("{", ", ", "}")
                Log[F].info(
                  s"New last finalized block hashes are ${mainParentFinalizedStr -> null}, ${secondaryParentsFinalizedStr -> null}."
                ) >> lfbRef.set(mainParent) >> EventEmitter[F]
                  .newLastFinalizedBlock(mainParent, secondary)
              }
            }
      } yield ()
    }

  /** Check that either we have the block already scheduled but missing dependencies, or it's in the store */
  def contains(
      block: Block
  ): F[Boolean] =
    BlockStorage[F].contains(block.blockHash)

  /** Return the list of tips. */
  def estimator(
      dag: DagRepresentation[F],
      lfbHash: BlockHash,
      latestMessagesHashes: Map[ByteString, Set[BlockHash]],
      equivocators: Set[Validator]
  ): F[NonEmptyList[BlockHash]] =
    Metrics[F].timer("estimator") {
      Estimator.tips[F](
        dag,
        lfbHash,
        latestMessagesHashes,
        equivocators
      )
    }

  /*
   * Logic:
   *  -Score each of the DAG heads extracted from the block messages via GHOST
   *  (Greedy Heaviest-Observed Sub-Tree)
   *  -Let P = subset of heads such that P contains no conflicts and the total score is maximized
   *  -Let R = subset of deploy messages which are not included in DAG obtained by following blocks in P
   *  -If R is non-empty then create a new block with parents equal to P and (non-conflicting) txns obtained from R
   *  -Else if R is empty and |P| > 1 then create a block with parents equal to P and no transactions
   *  -Else None
   *
   *  TODO: Make this return Either so that we get more information about why not block was
   *  produced (no deploys, already processing, no validator id)
   */
  def createMessage(canCreateBallot: Boolean): F[CreateBlockStatus] = validatorId match {
    case Some(ValidatorIdentity(publicKey, privateKey, sigAlgorithm)) =>
      Metrics[F].timer("createBlock") {
        for {
          dag                 <- dag
          latestMessages      <- dag.latestMessages
          latestMessageHashes = latestMessages.mapValues(_.map(_.messageHash))
          equivocators        <- dag.getEquivocators
          lfbHash             <- lfbRef.get
          // Tips can be either ballots or blocks.
          tipHashes <- estimator(dag, lfbHash, latestMessageHashes, equivocators)
          tips      <- tipHashes.traverse(ProtoUtil.unsafeGetBlock[F])
          _ <- Log[F].info(
                s"Estimates are ${tipHashes.toList.map(PrettyPrinter.buildString).mkString(", ") -> "tips"}"
              )
          _ <- Log[F].info(
                s"Fork-choice is ${PrettyPrinter.buildString(tipHashes.head) -> "block"}."
              )
          // Merged makes sure that we only get blocks.
          merged  <- ExecEngineUtil.merge[F](tips, dag).timer("mergeTipsEffects")
          parents = merged.parents
          _ <- Log[F].info(
                s"${parents.size} parents out of ${tipHashes.size} latest blocks will be used."
              )
          // Push any unfinalized deploys which are still in the buffer back to pending state if the
          // blocks they were contained have become orphans since we last tried to propose a block.
          // Doing this here rather than after adding blocks because it's quite costly; the downside
          // is that the auto-proposer will not pick up the change in the pending set immediately.
          // Requeueing operation is changing status of orphaned deploys in the DB.
          // It's done as a transaction. If operation won't make it in time before
          // immediate `createProposal`, those deploys will be considered for the next block.
          _ <- (DeployBuffer.requeueOrphanedDeploys[F](merged.parents.map(_.blockHash).toSet) >>= {
                requeued =>
                  Log[F].info(s"Re-queued $requeued orphaned deploys.").whenA(requeued > 0)
              }).forkAndLog
          timestamp <- Time[F].currentMillis
          props     <- CreateMessageProps(publicKey, latestMessages, merged)
          remainingHashes <- DeployBuffer.remainingDeploys[F](
                              dag,
                              parents.map(_.blockHash).toSet,
                              timestamp,
                              props.configuration.deployConfig
                            )
          proposal <- if (remainingHashes.nonEmpty) {
                       createProposal(
                         dag,
                         props,
                         latestMessages,
                         merged,
                         remainingHashes,
                         publicKey,
                         privateKey,
                         sigAlgorithm,
                         timestamp,
                         lfbHash
                       )
                     } else if (canCreateBallot && merged.parents.nonEmpty) {
                       createBallot(
                         latestMessages,
                         merged,
                         publicKey,
                         privateKey,
                         sigAlgorithm,
                         lfbHash
                       )
                     } else {
                       CreateBlockStatus.noNewDeploys.pure[F]
                     }
          signedBlock = proposal match {
            case Created(block) =>
              Created(signBlock(block, privateKey, sigAlgorithm))
            case _ => proposal
          }
        } yield signedBlock
      }
    case None => CreateBlockStatus.readOnlyMode.pure[F]
  }

  def lastFinalizedBlock: F[Block] =
    for {
      lastFinalizedBlockHash <- lfbRef.get
      block                  <- ProtoUtil.unsafeGetBlock[F](lastFinalizedBlockHash)
    } yield block

  // Collection of props for creating blocks or ballots.
  private case class CreateMessageProps(
      justifications: Seq[Justification],
      parents: Seq[BlockHash],
      rank: Long,
      protocolVersion: ProtocolVersion,
      configuration: Config,
      validatorSeqNum: Int,
      validatorPrevBlockHash: ByteString
  )
  private object CreateMessageProps {
    def apply(
        validatorId: Keys.PublicKey,
        latestMessages: Map[ByteString, Set[Message]],
        merged: ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]
    ): F[CreateMessageProps] = {
      // We ensure that only the justifications given in the block are those
      // which are bonded validators in the chosen parent. This is safe because
      // any latest message not from a bonded validator will not change the
      // final fork-choice.
      val bondedValidators = bonds(merged.parents.head).map(_.validatorPublicKey).toSet
      val bondedLatestMsgs = latestMessages.filter { case (v, _) => bondedValidators.contains(v) }
      // TODO: Remove redundant justifications.
      val justifications = toJustification(latestMessages.values.flatten.toSeq)
      // Start numbering from 1 (validator's first block seqNum = 1)
      val latestMessage = latestMessages
        .get(ByteString.copyFrom(validatorId))
        .map(_.maxBy(_.validatorMsgSeqNum))
      val validatorSeqNum        = latestMessage.fold(1)(_.validatorMsgSeqNum + 1)
      val validatorPrevBlockHash = latestMessage.fold(ByteString.EMPTY)(_.messageHash)
      for {
        // `bondedLatestMsgs` won't include Genesis block
        // and in the case when it becomes the main parent we want to include its rank
        // when calculating it for the current block.
        rank <- MonadThrowable[F].fromTry(
                 merged.parents.toList
                   .traverse(Message.fromBlock(_))
                   .map(_.toSet | bondedLatestMsgs.values.flatten.toSet)
                   .map(set => ProtoUtil.nextRank(set.toList))
               )
        config          <- CasperLabsProtocol[F].configAt(rank)
        protocolVersion <- CasperLabsProtocol[F].versionAt(rank)
      } yield CreateMessageProps(
        justifications,
        merged.parents.map(_.blockHash).toSeq,
        rank,
        protocolVersion,
        config,
        validatorSeqNum,
        validatorPrevBlockHash
      )
    }
  }

  /** Execute a set of deploys in the context of chosen parents. Compile them into a block if everything goes fine. */
  private def createProposal(
      dag: DagRepresentation[F],
      props: CreateMessageProps,
      latestMessages: Map[ByteString, Set[Message]],
      merged: ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block],
      remainingHashes: Set[ByteString],
      validatorId: Keys.PublicKey,
      privateKey: Keys.PrivateKey,
      sigAlgorithm: SignatureAlgorithm,
      timestamp: Long,
      lfbHash: BlockHash
  ): F[CreateBlockStatus] =
    Metrics[F].timer("createProposal") {
      val deployStream =
        DeployStorageReader[F]
          .getByHashes(remainingHashes)
          .through(
            DeployFilters.Pipes.dependenciesMet[F](dag, props.parents.toSet)
          )
      (for {
        checkpoint <- ExecEngineUtil
                       .computeDeploysCheckpoint[F](
                         merged,
                         deployStream,
                         timestamp,
                         props.protocolVersion,
                         props.rank,
                         upgrades
                       )
        result <- Sync[F]
                   .delay {
                     if (checkpoint.deploysForBlock.isEmpty) {
                       CreateBlockStatus.noNewDeploys
                     } else {
                       val block = ProtoUtil.block(
                         props.justifications,
                         checkpoint.preStateHash,
                         checkpoint.postStateHash,
                         checkpoint.bondedValidators,
                         checkpoint.deploysForBlock,
                         props.protocolVersion,
                         merged.parents.map(_.blockHash),
                         props.validatorSeqNum,
                         props.validatorPrevBlockHash,
                         chainName,
                         timestamp,
                         props.rank,
                         validatorId,
                         privateKey,
                         sigAlgorithm,
                         lfbHash
                       )
                       CreateBlockStatus.created(block)
                     }
                   }
                   .timer("blockInstance")
      } yield result).handleErrorWith {
        case ex @ SmartContractEngineError(error) =>
          Log[F]
            .error(
              s"Execution Engine returned an error while processing deploys: $error"
            )
            .as(CreateBlockStatus.internalDeployError(ex))
        case NonFatal(ex) =>
          Log[F]
            .error(
              s"Critical error encountered while processing deploys: $ex"
            )
            .as(CreateBlockStatus.internalDeployError(ex))
      }
    }

  private def createBallot(
      latestMessages: Map[ByteString, Set[Message]],
      merged: ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block],
      validatorId: Keys.PublicKey,
      privateKey: Keys.PrivateKey,
      sigAlgorithm: SignatureAlgorithm,
      keyBlockHash: ByteString
  ): F[CreateBlockStatus] =
    for {
      now    <- Time[F].currentMillis
      props  <- CreateMessageProps(validatorId, latestMessages, merged)
      parent = merged.parents.head
      block = ProtoUtil.ballot(
        props.justifications,
        parent.getHeader.getState.postStateHash,
        parent.getHeader.getState.bonds,
        props.protocolVersion,
        parent.blockHash,
        props.validatorSeqNum,
        props.validatorPrevBlockHash,
        chainName,
        now,
        props.rank,
        validatorId,
        privateKey,
        sigAlgorithm,
        keyBlockHash
      )
    } yield CreateBlockStatus.created(block)

  // MultiParentCasper Exposes the block DAG to those who need it.
  def dag: F[DagRepresentation[F]] =
    DagStorage[F].getRepresentation

  /** Remove all the blocks that were successfully added from the block buffer and the dependency DAG. */
  private def removeAdded(
      attempts: List[(Block, BlockStatus)],
      canRemove: BlockStatus => Boolean
  ): F[Unit] = {
    val addedBlocks = attempts.collect {
      case (block, status) if canRemove(status) => block
    }.toList

    val addedBlockHashes = addedBlocks.map(_.blockHash)

    // Mark deploys we have observed in blocks as processed
    val processedDeploys =
      attempts
        .collect { case (block, status) if status.inDag => block }
        .flatMap(block => block.getBody.deploys.map(_.getDeploy))

    DeployStorageWriter[F].markAsProcessed(processedDeploys) >>
      statelessExecutor.removeDependencies(addedBlockHashes)
  }

  /** The new gossiping first syncs the missing DAG, then downloads and adds the blocks in topological order.
    * However the EquivocationDetector wants to know about dependencies so it can assign different statuses,
    * so we'll make the synchronized DAG known via a partial block message, so any missing dependencies can
    * be tracked, i.e. Casper will know about the pending graph.  */
  def addMissingDependencies(block: Block): F[Unit] =
    statelessExecutor.addMissingDependencies(block)
}

object MultiParentCasperImpl {

  def create[F[_]: Concurrent: Log: Metrics: Time: BlockStorage: DagStorage: DeployBuffer: FinalityStorage: ExecutionEngineService: DeployStorage: Validation: CasperLabsProtocol: Cell[
    *[_],
    CasperState
  ]: DeploySelection: EventEmitter](
      semaphoreMap: SemaphoreMap[F, ByteString],
      statelessExecutor: StatelessExecutor[F],
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      chainName: String,
      minTtl: FiniteDuration,
      upgrades: Seq[ipc.ChainSpec.UpgradePoint],
      faultToleranceThreshold: Double = 0.1,
      lfbRef: Ref[F, BlockHash]
  ): F[MultiParentCasper[F]] =
    for {
      dag <- DagStorage[F].getRepresentation
      lfb <- lfbRef.get
      finalityDetector <- FinalityDetectorVotingMatrix
                           .of[F](
                             dag,
                             lfb,
                             faultToleranceThreshold
                           )
      implicit0(multiParentFinalizer: MultiParentFinalizer[F]) <- MultiParentFinalizer.empty[F](
                                                                   dag,
                                                                   lfb,
                                                                   finalityDetector
                                                                 )
    } yield new MultiParentCasperImpl[F](
      semaphoreMap,
      statelessExecutor,
      validatorId,
      genesis,
      chainName,
      minTtl,
      upgrades,
      lfbRef
    )

  /** Component purely to validate, execute and store blocks.
    * Even the Genesis, to create it in the first place. */
  class StatelessExecutor[F[_]: Sync: Time: Log: BlockStorage: DagStorage: DeployStorage: ExecutionEngineService: Metrics: DeployStorageWriter: Validation: CasperLabsProtocol: Fs2Compiler: EventEmitter](
      validatorId: Option[Keys.PublicKey],
      chainName: String,
      upgrades: Seq[ipc.ChainSpec.UpgradePoint],
      // NOTE: There was a race condition where probably a block was being removed at the same time
      // as its child was scheduled. The parent was still not in the DAG database, so it was considered
      // a missing dependency. It got removed and immediately added back, which later caused an assertion failure.
      // This semaphore should protect against state mutations that should be serialised, including reads.
      semaphore: Semaphore[F]
  ) {
    //TODO pull out
    implicit val functorRaiseInvalidBlock = validation.raiseValidateErrorThroughApplicativeError[F]

    /* Execute the block to get the effects then do some more validation.
     * Save the block if everything checks out.
     * We want to catch equivocations only after we confirm that the block completing
     * the equivocation is otherwise valid. */
    def validateAndAddBlock(
        maybeContext: Option[StatelessExecutor.Context],
        block: Block
    )(implicit state: Cell[F, CasperState]): F[BlockStatus] = {
      import io.casperlabs.casper.validation.ValidationImpl.metricsSource
      Metrics[F].timer("validateAndAddBlock") {
        val hashPrefix = PrettyPrinter.buildString(block.blockHash)
        val validationStatus = (for {
          _   <- Log[F].info(s"Attempting to add ${hashPrefix -> "block"} to the DAG.")
          dag <- DagStorage[F].getRepresentation
          _ <- Validation[F].blockFull(
                block,
                dag,
                chainName,
                maybeContext.map(_.genesis)
              )
          casperState <- Cell[F, CasperState].read
          // Confirm the parents are correct (including checking they commute) and capture
          // the effect needed to compute the correct pre-state as well.
          _ <- Log[F].debug(s"Validating the parents of ${hashPrefix -> "block"}")
          merged <- maybeContext.fold(
                     ExecEngineUtil.MergeResult
                       .empty[ExecEngineUtil.TransformMap, Block]
                       .pure[F]
                   ) { _ =>
                     Validation[F].parents(block, dag)
                   }
          _ <- Log[F].debug(s"Computing the pre-state hash of ${hashPrefix -> "block"}")
          preStateHash <- ExecEngineUtil
                           .computePrestate[F](merged, block.getHeader.rank, upgrades)
                           .timer("computePrestate")
          _ <- Log[F].debug(s"Computing the effects for ${hashPrefix -> "block"}")
          blockEffects <- ExecEngineUtil
                           .effectsForBlock[F](block, preStateHash)
                           .recoverWith {
                             case NonFatal(ex) =>
                               Log[F].error(
                                 s"Could not calculate effects for ${hashPrefix -> "block"}: $ex"
                               ) *>
                                 FunctorRaise[F, InvalidBlock].raise(InvalidTransaction)
                           }
                           .timer("effectsForBlock")
          gasSpent = block.getBody.deploys.foldLeft(0L) { case (acc, next) => acc + next.cost }
          _ <- Metrics[F]
                .incrementCounter("gas_spent", gasSpent)
          _ <- Log[F].debug(s"Validating the transactions in ${hashPrefix -> "block"}")
          _ <- Validation[F].transactions(
                block,
                preStateHash,
                blockEffects
              )
          _ <- Log[F].debug(s"Validating neglection for ${hashPrefix -> "block"}")
          _ <- Validation[F]
                .neglectedInvalidBlock(
                  block,
                  casperState.invalidBlockTracker
                )
          _       <- Log[F].debug(s"Checking equivocation for ${hashPrefix -> "block"}")
          message <- MonadThrowable[F].fromTry(Message.fromBlock(block))
          _ <- EquivocationDetector
                .checkEquivocationWithUpdate[F](dag, message)
                .timer("checkEquivocationsWithUpdate")
          _ <- Log[F].debug(s"Block effects calculated for ${hashPrefix -> "block"}")
        } yield blockEffects).attempt

        validationStatus.flatMap {
          case Right(effects) =>
            addEffects(Valid, block, effects).as(Valid)

          case Left(DropErrorWrapper(invalid)) =>
            // These exceptions are coming from the validation checks that used to happen outside attemptAdd,
            // the ones that returned boolean values.
            (invalid: BlockStatus).pure[F]

          case Left(ValidateErrorWrapper(EquivocatedBlock))
              if validatorId.map(ByteString.copyFrom).exists(_ == block.validatorPublicKey) =>
            addEffects(SelfEquivocatedBlock, block, BlockEffects.empty).as(SelfEquivocatedBlock)

          case Left(ValidateErrorWrapper(invalid)) =>
            addEffects(invalid, block, BlockEffects.empty).as(invalid)

          case Left(ex) =>
            for {
              _ <- Log[F].error(
                    s"Unexpected exception during validation of ${PrettyPrinter
                      .buildString(block.blockHash) -> "block"}: $ex"
                  )
              _ <- ex.raiseError[F, BlockStatus]
            } yield UnexpectedBlockException(ex)
        }
      }
    }

    // TODO: Handle slashing
    /** Either store the block with its transformation,
      * or add it to the buffer in case the dependencies are missing. */
    def addEffects(
        status: BlockStatus,
        block: Block,
        transforms: BlockEffects
    )(implicit state: Cell[F, CasperState]): F[Unit] =
      status match {
        //Add successful! Send block to peers, log success, try to add other blocks
        case Valid =>
          for {
            _ <- addToState(block, transforms)
            _ <- Log[F].info(
                  s"Added ${PrettyPrinter.buildString(block.blockHash) -> "block"}"
                )
          } yield ()

        case MissingBlocks =>
          addMissingDependencies(block)

        case EquivocatedBlock | SelfEquivocatedBlock =>
          for {
            _ <- addToState(block, transforms)
            _ <- Log[F].info(
                  s"Added equivocated ${PrettyPrinter.buildString(block.blockHash) -> "block"}"
                )
          } yield ()

        case InvalidUnslashableBlock | InvalidBlockNumber | InvalidParents | InvalidSequenceNumber |
            InvalidPrevBlockHash | NeglectedInvalidBlock | InvalidTransaction | InvalidBondsCache |
            InvalidRepeatDeploy | InvalidChainName | InvalidBlockHash | InvalidDeployCount |
            InvalidDeployHash | InvalidDeploySignature | InvalidPreStateHash |
            InvalidPostStateHash | InvalidTargetHash | InvalidDeployHeader |
            InvalidDeployChainName | DeployDependencyNotMet | DeployExpired | DeployFromFuture |
            SwimlaneMerged =>
          handleInvalidBlockEffect(status, block)

        case Processing | Processed =>
          throw new RuntimeException(s"A block should not be processing at this stage.")

        case UnexpectedBlockException(ex) =>
          Log[F].error(s"Encountered exception in while processing ${PrettyPrinter
            .buildString(block.blockHash) -> "block"}: $ex")
      }

    /** Remember a block as being invalid, then save it to storage. */
    private def handleInvalidBlockEffect(
        status: BlockStatus,
        block: Block
    )(implicit state: Cell[F, CasperState]): F[Unit] =
      for {
        _ <- Log[F].warn(
              s"Recording invalid ${PrettyPrinter.buildString(block.blockHash) -> "block"} for $status."
            )
        // TODO: Slash block for status except InvalidUnslashableBlock
        // TODO: Persist invalidBlockTracker into Dag
        _ <- Cell[F, CasperState].modify { s =>
              s.copy(invalidBlockTracker = s.invalidBlockTracker + block.blockHash)
            }
      } yield ()

    /** Save the block to the block and DAG storage. */
    private def addToState(
        block: Block,
        blockEffects: BlockEffects
    ): F[Unit] =
      for {
        _ <- semaphore.withPermit {
              BlockStorage[F]
                .put(block.blockHash, block, blockEffects.effects)
            }
        blockInfo <- BlockAPI.getBlockInfo[F](
                      Base16.encode(block.blockHash.toByteArray),
                      BlockInfo.View.FULL
                    )
        _ <- EventEmitter[F].blockAdded(blockInfo)
      } yield ()

    /** Check if the block has dependencies that we don't have in store.
      * Add those to the dependency DAG. */
    def addMissingDependencies(
        block: Block
    )(implicit state: Cell[F, CasperState]): F[Unit] =
      semaphore.withPermit {
        for {
          dag                 <- DagStorage[F].getRepresentation
          missingDependencies <- dependenciesHashesOf(block).filterA(dag.contains(_).map(!_))
          _ <- Cell[F, CasperState].modify { s =>
                s.copy(
                  dependencyDag = missingDependencies.foldLeft(s.dependencyDag) {
                    case (deps, ancestorHash) => deps.add(ancestorHash, block.blockHash)
                  }
                )
              }
        } yield ()
      }

    /** Remove block relationships we scheduled earlier after blocks have been added. */
    def removeDependencies(
        addedBlocksHashes: List[ByteString]
    )(implicit state: Cell[F, CasperState]): F[Unit] =
      semaphore.withPermit {
        Cell[F, CasperState].modify { s =>
          s.copy(
            dependencyDag = addedBlocksHashes.foldLeft(s.dependencyDag) {
              case (dag, blockHash) =>
                dag.remove(blockHash)
            }
          )
        }
      }
  }

  object StatelessExecutor {
    case class Context(genesis: Block, lastFinalizedBlockHash: BlockHash)

    def establishMetrics[F[_]: Metrics]: F[Unit] = {
      implicit val src = CasperMetricsSource
      Metrics[F].incrementCounter("gas_spent", 0L)
    }

    def create[F[_]: Concurrent: Time: Log: BlockStorage: DagStorage: ExecutionEngineService: Metrics: DeployStorage: Validation: CasperLabsProtocol: Fs2Compiler: EventEmitter](
        validatorId: Option[Keys.PublicKey],
        chainName: String,
        upgrades: Seq[ipc.ChainSpec.UpgradePoint]
    ): F[StatelessExecutor[F]] =
      for {
        _ <- establishMetrics[F]
        s <- Semaphore[F](1)
      } yield new StatelessExecutor[F](validatorId, chainName, upgrades, s)
  }

  /** Encapsulating all methods that might use peer-to-peer communication. */
  @typeclass trait Broadcaster[F[_]] {
    def networkEffects(
        block: Block,
        status: BlockStatus
    ): F[Unit]
  }

  object Broadcaster {

    /** Network access using the new RPC style gossiping. */
    def fromGossipServices[F[_]: Applicative](
        validatorId: Option[ValidatorIdentity],
        relaying: gossiping.Relaying[F]
    ): Broadcaster[F] = new Broadcaster[F] {

      private val maybeOwnPublicKey = validatorId map {
        case ValidatorIdentity(publicKey, _, _) =>
          ByteString.copyFrom(publicKey)
      }

      def networkEffects(
          block: Block,
          status: BlockStatus
      ): F[Unit] =
        status match {
          case Valid | EquivocatedBlock =>
            maybeOwnPublicKey match {
              case Some(key) if key == block.getHeader.validatorPublicKey =>
                relaying.relay(List(block.blockHash)).void
              case _ =>
                // We were adding somebody else's block. The DownloadManager did the gossiping.
                ().pure[F]
            }

          case SelfEquivocatedBlock =>
            // Don't relay block with which a node has equivocated.
            ().pure[F]

          case MissingBlocks =>
            throw new RuntimeException(
              "Impossible! The DownloadManager should not tell Casper about blocks with missing dependencies!"
            )

          case InvalidUnslashableBlock | InvalidBlockNumber | InvalidParents |
              InvalidSequenceNumber | InvalidPrevBlockHash | NeglectedInvalidBlock |
              InvalidTransaction | InvalidBondsCache | InvalidChainName | InvalidBlockHash |
              InvalidDeployCount | InvalidPreStateHash | InvalidPostStateHash | SwimlaneMerged |
              InvalidTargetHash | Processing | Processed =>
            ().pure[F]

          case InvalidRepeatDeploy | InvalidChainName | InvalidDeployHash | InvalidDeploySignature |
              InvalidDeployChainName | InvalidDeployHeader | DeployDependencyNotMet |
              DeployExpired =>
            ().pure[F]

          case UnexpectedBlockException(_) | DeployFromFuture =>
            ().pure[F]
        }
    }

    def noop[F[_]: Applicative]: Broadcaster[F] = new Broadcaster[F] {
      override def networkEffects(block: Block, status: BlockStatus): F[Unit] = Applicative[F].unit
    }
  }

}
