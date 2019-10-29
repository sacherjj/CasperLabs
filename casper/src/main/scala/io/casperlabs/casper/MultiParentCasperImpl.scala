package io.casperlabs.casper

import cats.data.NonEmptyList
import cats.effect.concurrent.Semaphore
import cats.effect.{Resource, Sync}
import cats.implicits._
import cats.mtl.FunctorRaise
import cats.{Applicative, Monad}
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.DeployFilters.filterDeploysNotInPast
import io.casperlabs.casper.equivocations.{EquivocationDetector}
import io.casperlabs.casper.finality.singlesweep.FinalityDetector
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.execengine.{ExecEngineUtil}
import io.casperlabs.casper.validation.Errors._
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib._
import io.casperlabs.comm.gossiping
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.ipc.ValidateRequest
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.{Message, SmartContractEngineError, Weight}
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader, DeployStorageWriter}

import scala.util.control.NonFatal

/**
  * Encapsulates mutable state of the MultiParentCasperImpl
  **
  *
  * @param blockBuffer
  * @param invalidBlockTracker
  */
final case class CasperState(
    invalidBlockTracker: Set[BlockHash] = Set.empty[BlockHash],
    dependencyDag: DoublyLinkedDag[BlockHash] = BlockDependencyDag.empty
)

@silent("is never used")
class MultiParentCasperImpl[F[_]: Sync: Log: Metrics: Time: FinalityDetector: BlockStorage: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: DeployStorage: Validation: Fs2Compiler: DeploySelection: CasperLabsProtocolVersions](
    validatorSemaphoreMap: SemaphoreMap[F, ByteString],
    statelessExecutor: MultiParentCasperImpl.StatelessExecutor[F],
    broadcaster: MultiParentCasperImpl.Broadcaster[F],
    validatorId: Option[ValidatorIdentity],
    genesis: Block,
    chainName: String,
    upgrades: Seq[ipc.ChainSpec.UpgradePoint],
    val faultToleranceThreshold: Float = 0f
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
            DagRepresentation[F],
            Block
        ) => F[(BlockStatus, DagRepresentation[F])]
    ) =
      validatorSemaphoreMap.withPermit(block.getHeader.validatorPublicKey) {
        for {
          dag       <- dag
          blockHash = block.blockHash
          inDag     <- dag.contains(blockHash)
          attempts <- if (inDag) {
                       Log[F]
                         .info(
                           s"Block ${PrettyPrinter.buildString(blockHash)} has already been processed by another thread."
                         ) *>
                         List(block -> BlockStatus.processed).pure[F]
                     } else {
                       // This might be the first time we see this block, or it may not have been added to the state
                       // because it was an IgnorableEquivocation, but then we saw a child and now we got it again.
                       internalAddBlock(block, dag, validateAndAddBlock)
                     }
          // This method could just return the block hashes it created,
          // but for now it does gossiping as well. The methods return the full blocks
          // because for missing blocks it's not yet saved to the database.
          _ <- attempts.traverse {
                case (attemptedBlock, status) =>
                  broadcaster.networkEffects(attemptedBlock, status)
              }
        } yield attempts.head._2
      }

    val handleInvalidTimestamp =
      (_: Option[StatelessExecutor.Context], dag: DagRepresentation[F], block: Block) =>
        statelessExecutor
          .addEffects(InvalidUnslashableBlock, block, Seq.empty, dag)
          .tupleLeft(InvalidUnslashableBlock: BlockStatus)

    // If the block timestamp is in the future, wait some time before adding it,
    // so we won't include it as a justification from the future.
    Validation[F].preTimestamp(block).attempt.flatMap {
      case Right(None) =>
        addBlock(statelessExecutor.validateAndAddBlock)
      case Right(Some(delay)) =>
        Log[F].info(
          s"Block ${PrettyPrinter.buildString(block)} is ahead for $delay from now, will retry adding later"
        ) >> Time[F].sleep(delay) >> addBlock(statelessExecutor.validateAndAddBlock)
      case _ =>
        Log[F].warn(validation.ignore(block, "block timestamp exceeded threshold")) >> addBlock(
          handleInvalidTimestamp
        )
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
          DagRepresentation[F],
          Block
      ) => F[(BlockStatus, DagRepresentation[F])]
  ): F[List[(Block, BlockStatus)]] = Metrics[F].timer("addBlock") {
    for {
      lastFinalizedBlockHash <- LastFinalizedBlockHashContainer[F].get
      (status, updatedDag) <- validateAndAddBlock(
                               StatelessExecutor.Context(genesis, lastFinalizedBlockHash).some,
                               dag,
                               block
                             )
      _          <- removeAdded(List(block -> status), canRemove = _ != MissingBlocks)
      hashPrefix = PrettyPrinter.buildString(block.blockHash)
      // Update the last finalized block; remove finalized deploys from the buffer
      _ <- Log[F].debug(s"Updating last finalized block after adding ${hashPrefix}")
      _ <- updateLastFinalizedBlock(updatedDag)
      // Remove any deploys from the buffer which are in finalized blocks.
      _ <- Log[F].debug(s"Removing finalized deploys after adding ${hashPrefix}")
      _ <- removeFinalizedDeploys(updatedDag)
      _ <- Log[F].debug(s"Finished adding ${hashPrefix}")
    } yield List(block -> status)
  }

  private def updateLastFinalizedBlock(dag: DagRepresentation[F]): F[Unit] =
    Metrics[F].timer("updateLastFinalizedBlock") {

      /** Go from the last finalized block and visit all children that can be finalized now.
        * Remove all of the deploys that are in any of them as they won't have to be attempted again. */
      def loop(acc: BlockHash): F[BlockHash] =
        for {
          childrenHashes <- dag
                             .children(acc)
                             .map(_.toList)
          finalizedChildren <- childrenHashes.filterA(isGreaterThanFaultToleranceThreshold(dag, _))
          newFinalizedBlock <- if (finalizedChildren.isEmpty) {
                                acc.pure[F]
                              } else {
                                finalizedChildren.traverse(loop).map(_.head)
                              }
        } yield newFinalizedBlock

      for {
        lastFinalizedBlockHash        <- LastFinalizedBlockHashContainer[F].get
        updatedLastFinalizedBlockHash <- loop(lastFinalizedBlockHash)
        _                             <- LastFinalizedBlockHashContainer[F].set(updatedLastFinalizedBlockHash)
        _ <- Log[F].info(
              s"New last finalized block hash is ${PrettyPrinter.buildString(updatedLastFinalizedBlockHash)}."
            )
      } yield ()
    }

  /** Remove deploys from the buffer which are included in block that are finalized. */
  private def removeFinalizedDeploys(dag: DagRepresentation[F]): F[Unit] =
    Metrics[F].timer("removeFinalizedDeploys") {
      for {
        deployHashes <- DeployStorageReader[F].readProcessedHashes
        blockHashes <- deployHashes
                        .traverse { deployHash =>
                          BlockStorage[F]
                            .findBlockHashesWithDeployHash(deployHash)
                        }
                        .map(_.flatten.distinct)

        lastFinalizedBlock <- (LastFinalizedBlockHashContainer[F].get >>= dag.lookup).map(_.get)

        finalizedBlockHashes <- blockHashes.filterA { blockHash =>
                                 // NODE-930. To be replaced when we implement finality streams.
                                 dag.lookup(blockHash) map {
                                   // This is just a mock finality formula that still allows some
                                   // chance for orhpans to be re-queued in blocks ahead of the
                                   // last finalized blocks.
                                   _.fold(false)(_.rank <= lastFinalizedBlock.rank)
                                 }
                               }

        _ <- finalizedBlockHashes.traverse { blockHash =>
              removeDeploysInBlock(blockHash) flatMap { removed =>
                Log[F]
                  .info(
                    s"Removed $removed deploys from deploy history as we finalized block ${PrettyPrinter
                      .buildString(blockHash)}"
                  )
                  .whenA(removed > 0L)
              }
            }
      } yield ()
    }

  /** Remove deploys from the history which are included in a just finalised block. */
  private def removeDeploysInBlock(blockHash: BlockHash): F[Long] =
    for {
      block              <- ProtoUtil.unsafeGetBlock[F](blockHash)
      deploysToRemove    = block.body.get.deploys.map(_.deploy.get).toList
      initialHistorySize <- DeployStorageReader[F].sizePendingOrProcessed()
      _                  <- DeployStorageWriter[F].markAsFinalized(deploysToRemove)
      deploysRemoved <- DeployStorageReader[F]
                         .sizePendingOrProcessed()
                         .map(after => initialHistorySize - after)
    } yield deploysRemoved

  /*
   * On the first pass, block B is finalized if B's main parent block is finalized
   * and the safety oracle says B's normalized fault tolerance is above the threshold.
   * On the second pass, block B is finalized if any of B's children blocks are finalized.
   *
   * TODO: Implement the second pass in BlockAPI
   */
  private def isGreaterThanFaultToleranceThreshold(
      dag: DagRepresentation[F],
      blockHash: BlockHash
  ): F[Boolean] =
    for {
      faultTolerance <- FinalityDetector[F].normalizedFaultTolerance(
                         dag,
                         blockHash
                       )
      _ <- Log[F].trace(
            s"Fault tolerance for block ${PrettyPrinter.buildString(blockHash)} is $faultTolerance; threshold is $faultToleranceThreshold"
          )
    } yield faultTolerance > faultToleranceThreshold

  /** Check that either we have the block already scheduled but missing dependencies, or it's in the store */
  def contains(
      block: Block
  ): F[Boolean] =
    BlockStorage[F].contains(block.blockHash)

  /** Add a deploy to the buffer, if the code passes basic validation. */
  def deploy(deploy: Deploy): F[Either[Throwable, Unit]] = validatorId match {
    case Some(_) =>
      addDeploy(deploy)
    case None =>
      new IllegalStateException(s"Node is in read-only mode.").asLeft[Unit].pure[F].widen
  }

  private def validateDeploy(deploy: Deploy): F[Unit] = {
    def illegal(msg: String): F[Unit] =
      MonadThrowable[F].raiseError(new IllegalArgumentException(msg))

    def check(msg: String)(f: F[Boolean]): F[Unit] =
      f flatMap { ok =>
        illegal(msg).whenA(!ok)
      }

    for {
      _ <- (deploy.getBody.session, deploy.getBody.payment) match {
            case (None, _) | (_, None) | (Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty)), _) |
                (_, Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty))) =>
              illegal(s"Deploy was missing session and/or payment code.")
            case _ => ().pure[F]
          }
      _ <- check("Invalid deploy hash.")(Validation[F].deployHash(deploy))
      _ <- check("Invalid deploy signature.")(Validation[F].deploySignature(deploy))
      _ <- Validation[F].deployHeader(deploy, chainName) >>= { headerErrors =>
            illegal(headerErrors.map(_.errorMessage).mkString("\n"))
              .whenA(headerErrors.nonEmpty)
          }
    } yield ()
  }

  /** Add a deploy to the buffer, to be executed later. */
  private def addDeploy(deploy: Deploy): F[Either[Throwable, Unit]] =
    (for {
      _ <- validateDeploy(deploy)
      _ <- DeployStorageWriter[F].addAsPending(List(deploy))
      _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(deploy)}")
    } yield ()).attempt

  /** Return the list of tips. */
  def estimator(
      dag: DagRepresentation[F],
      latestMessagesHashes: Map[ByteString, Set[BlockHash]],
      equivocators: Set[Validator]
  ): F[List[BlockHash]] =
    Metrics[F].timer("estimator") {
      Estimator.tips[F](
        dag,
        genesis.blockHash,
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
  def createBlock: F[CreateBlockStatus] = validatorId match {
    case Some(ValidatorIdentity(publicKey, privateKey, sigAlgorithm)) =>
      Metrics[F].timer("createBlock") {
        for {
          dag                 <- dag
          latestMessages      <- dag.latestMessages
          latestMessageHashes = latestMessages.mapValues(_.map(_.messageHash))
          equivocators        <- dag.getEquivocators
          tipHashes           <- estimator(dag, latestMessageHashes, equivocators)
          tips                <- tipHashes.traverse(ProtoUtil.unsafeGetBlock[F])
          _ <- Log[F].info(
                s"Tip estimates: ${tipHashes.map(PrettyPrinter.buildString).mkString(", ")}"
              )
          _ <- Log[F].info(
                s"Fork-choice tip is block ${PrettyPrinter.buildString(tipHashes.head)}."
              )
          merged  <- ExecEngineUtil.merge[F](tips, dag)
          parents = merged.parents
          _ <- Log[F].info(
                s"${parents.size} parents out of ${tipHashes.size} latest blocks will be used."
              )

          // Push any unfinalized deploys which are still in the buffer back to pending state if the
          // blocks they were contained have become orphans since we last tried to propose a block.
          // Doing this here rather than after adding blocks because it's quite costly; the downside
          // is that the auto-proposer will not pick up the change in the pending set immediately.
          requeued <- requeueOrphanedDeploys(dag, tipHashes)
          _        <- Log[F].info(s"Re-queued ${requeued} orphaned deploys.").whenA(requeued > 0)

          timestamp       <- Time[F].currentMillis
          remainingHashes <- remainingDeploysHashes(dag, parents.map(_.blockHash).toSet, timestamp)
          proposal <- if (remainingHashes.nonEmpty || parents.length > 1) {
                       createProposal(
                         dag,
                         latestMessages,
                         merged,
                         remainingHashes,
                         publicKey,
                         privateKey,
                         sigAlgorithm,
                         timestamp
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
      lastFinalizedBlockHash <- LastFinalizedBlockHashContainer[F].get
      block                  <- ProtoUtil.unsafeGetBlock[F](lastFinalizedBlockHash)
    } yield block

  /** Get the deploys that are not present in the past of the chosen parents. */
  private def remainingDeploysHashes(
      dag: DagRepresentation[F],
      parents: Set[BlockHash],
      timestamp: Long
  ): F[Set[DeployHash]] = Metrics[F].timer("remainingDeploys") {
    // We have re-queued orphan deploys already, so we can just look at pending ones.
    val earlierPendingDeploys = DeployStorageReader[F].readPendingHashesAndHeaders
      .through(DeployFilters.Pipes.timestampBefore[F](timestamp))
    val unexpired = earlierPendingDeploys.through(DeployFilters.Pipes.notExpired[F](timestamp))

    for {
      unexpiredList <- unexpired.map(_._1).compile.toList
      // Make sure pending deploys have never been processed in the past cone of the new parents.
      validDeploys <- DeployFilters.filterDeploysNotInPast(dag, parents, unexpiredList).map(_.toSet)
      // anything with timestamp earlier than now and not included in the valid deploys
      // can be discarded as a duplicate and/or expired deploy
      deploysToDiscard <- earlierPendingDeploys.map(_._1).compile.to[Set].map(_ diff validDeploys)
      _ <- DeployStorageWriter[F]
            .markAsDiscardedByHashes(deploysToDiscard.toList.map((_, "Duplicate or expired")))
            .whenA(deploysToDiscard.nonEmpty)
    } yield validDeploys
  }

  /** If another node proposed a block which orphaned something proposed by this node,
    * and we still have these deploys in the `processedDeploys` buffer then put them
    * back into the `pendingDeploys` so that the `AutoProposer` can pick them up again.
    */
  private def requeueOrphanedDeploys(
      dag: DagRepresentation[F],
      tipHashes: List[BlockHash]
  ): F[Int] = Metrics[F].timer("requeueOrphanedDeploys") {
    for {
      // We actually need the tips which can be merged, the ones which we'd build on if we
      // attempted to create a new block.
      tips    <- tipHashes.traverse(ProtoUtil.unsafeGetBlock[F])
      merged  <- ExecEngineUtil.merge[F](tips, dag)
      parents = merged.parents
      // Consider deploys which this node has processed but hasn't finalized yet.
      processedDeploys <- DeployStorageReader[F].readProcessedHashes
      orphanedDeploys <- filterDeploysNotInPast(
                          dag,
                          parents.map(_.blockHash).toSet,
                          processedDeploys
                        )
      _ <- DeployStorageWriter[F]
            .markAsPendingByHashes(orphanedDeploys) whenA orphanedDeploys.nonEmpty
    } yield orphanedDeploys.size
  }

  //TODO: Need to specify SEQ vs PAR type block?
  /** Execute a set of deploys in the context of chosen parents. Compile them into a block if everything goes fine. */
  private def createProposal(
      dag: DagRepresentation[F],
      latestMessages: Map[ByteString, Set[Message]],
      merged: ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block],
      remainingHashes: Set[BlockHash],
      validatorId: Keys.PublicKey,
      privateKey: Keys.PrivateKey,
      sigAlgorithm: SignatureAlgorithm,
      timestamp: Long
  ): F[CreateBlockStatus] =
    Metrics[F].timer("createProposal") {
      //We ensure that only the justifications given in the block are those
      //which are bonded validators in the chosen parent. This is safe because
      //any latest message not from a bonded validator will not change the
      //final fork-choice.
      val bondedValidators = bonds(merged.parents.head).map(_.validatorPublicKey).toSet
      val bondedLatestMsgs = latestMessages.filter { case (v, _) => bondedValidators.contains(v) }
      // TODO: Remove redundant justifications.
      val justifications = toJustification(latestMessages.values.flatten.toSeq)
      val deployStream =
        DeployStorageReader[F]
          .getByHashes(remainingHashes)
          .through(
            DeployFilters.Pipes.dependenciesMet[F](dag, merged.parents.map(_.blockHash).toSet)
          )
      (for {
        // `bondedLatestMsgs` won't include Genesis block
        // and in the case when it becomes the main parent we want to include its rank
        // when calculating it for the current block.
        rank <- MonadThrowable[F].fromTry(
                 merged.parents.toList
                   .traverse(Message.fromBlock(_))
                   .map(_.toSet | bondedLatestMsgs.values.flatten.toSet)
                   .map(set => ProtoUtil.nextRank(set.toList))
               )
        protocolVersion <- CasperLabsProtocolVersions[F].versionAt(rank)
        checkpoint <- ExecEngineUtil.computeDeploysCheckpoint[F](
                       merged,
                       deployStream,
                       timestamp,
                       protocolVersion,
                       rank,
                       upgrades
                     )
      } yield {
        if (checkpoint.deploysForBlock.isEmpty) {
          CreateBlockStatus.noNewDeploys
        } else {
          // Start numbering from 1 (validator's first block seqNum = 1)
          val latestMessage =
            latestMessages.get(ByteString.copyFrom(validatorId)).map(_.maxBy(_.validatorMsgSeqNum))
          val validatorSeqNum        = latestMessage.fold(1)(_.validatorMsgSeqNum + 1)
          val validatorPrevBlockHash = latestMessage.fold(ByteString.EMPTY)(_.messageHash)
          val block = ProtoUtil.block(
            justifications,
            checkpoint.preStateHash,
            checkpoint.postStateHash,
            checkpoint.bondedValidators,
            checkpoint.deploysForBlock,
            protocolVersion,
            merged.parents.map(_.blockHash),
            validatorSeqNum,
            validatorPrevBlockHash,
            chainName,
            timestamp,
            rank,
            validatorId,
            privateKey,
            sigAlgorithm
          )
          CreateBlockStatus.created(block)
        }
      }).handleErrorWith {
        case ex @ SmartContractEngineError(error_msg) =>
          Log[F]
            .error(
              s"Execution Engine returned an error while processing deploys: $error_msg"
            )
            .as(CreateBlockStatus.internalDeployError(ex))
        case NonFatal(ex) =>
          Log[F]
            .error(
              s"Critical error encountered while processing deploys: ${ex.getMessage}",
              ex
            )
            .as(CreateBlockStatus.internalDeployError(ex))
      }
    }

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
    }.toSet

    val addedBlockHashes = addedBlocks.map(_.blockHash)

    // Mark deploys we have observed in blocks as processed
    val processedDeploys =
      addedBlocks.flatMap(block => block.getBody.deploys.map(_.getDeploy)).toList

    DeployStorageWriter[F].markAsProcessed(processedDeploys) >>
      Cell[F, CasperState].modify { s =>
        s.copy(
          dependencyDag = addedBlocks.foldLeft(s.dependencyDag) {
            case (dag, block) =>
              dag.remove(block.blockHash)
          }
        )
      }
  }

  /** The new gossiping first syncs the missing DAG, then downloads and adds the blocks in topological order.
    * However the EquivocationDetector wants to know about dependencies so it can assign different statuses,
    * so we'll make the synchronized DAG known via a partial block message, so any missing dependencies can
    * be tracked, i.e. Casper will know about the pending graph.  */
  def addMissingDependencies(block: Block): F[Unit] =
    for {
      dag <- dag
      _   <- statelessExecutor.addMissingDependencies(block, dag)
    } yield ()
}

object MultiParentCasperImpl {

  def create[F[_]: Sync: Log: Metrics: Time: FinalityDetector: BlockStorage: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: DeployStorage: Validation: CasperLabsProtocolVersions: Cell[
    ?[_],
    CasperState
  ]: DeploySelection](
      semaphoreMap: SemaphoreMap[F, ByteString],
      statelessExecutor: StatelessExecutor[F],
      broadcaster: Broadcaster[F],
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      chainName: String,
      upgrades: Seq[ipc.ChainSpec.UpgradePoint],
      faultToleranceThreshold: Float = 0f
  ): F[MultiParentCasper[F]] =
    for {
      dag <- DagStorage[F].getRepresentation
      lmh <- dag.latestMessageHashes
      // A stopgap solution to initialize the Last Finalized Block while we don't have the finality streams
      // that we can use to mark every final block in the database and just look up the latest upon restart.
      lca <- NonEmptyList.fromList(lmh.values.flatten.toList).fold(genesis.blockHash.pure[F]) {
              hashes =>
                DagOperations.latestCommonAncestorsMainParent[F](dag, hashes)
            }
      _ <- LastFinalizedBlockHashContainer[F].set(lca)
    } yield new MultiParentCasperImpl[F](
      semaphoreMap,
      statelessExecutor,
      broadcaster,
      validatorId,
      genesis,
      chainName,
      upgrades,
      faultToleranceThreshold
    )

  /** Component purely to validate, execute and store blocks.
    * Even the Genesis, to create it in the first place. */
  class StatelessExecutor[F[_]: MonadThrowable: Time: Log: BlockStorage: DagStorage: ExecutionEngineService: Metrics: DeployStorageWriter: Validation: FinalityDetector: LastFinalizedBlockHashContainer: CasperLabsProtocolVersions: Fs2Compiler](
      chainName: String,
      upgrades: Seq[ipc.ChainSpec.UpgradePoint]
  ) {
    //TODO pull out
    implicit val functorRaiseInvalidBlock = validation.raiseValidateErrorThroughApplicativeError[F]
    implicit val metricsSource            = CasperMetricsSource

    /* Execute the block to get the effects then do some more validation.
     * Save the block if everything checks out.
     * We want to catch equivocations only after we confirm that the block completing
     * the equivocation is otherwise valid. */
    def validateAndAddBlock(
        maybeContext: Option[StatelessExecutor.Context],
        dag: DagRepresentation[F],
        block: Block
    )(implicit state: Cell[F, CasperState]): F[(BlockStatus, DagRepresentation[F])] =
      Metrics[F].timer("validateAndAddBlock") {
        val validationStatus = (for {
          _ <- Log[F].info(
                s"Attempting to add ${PrettyPrinter.buildString(block)} to the DAG."
              )
          hashPrefix = PrettyPrinter.buildString(block.blockHash)
          _ <- Validation[F].blockFull(
                block,
                dag,
                chainName,
                maybeContext.map(_.genesis)
              )
          casperState <- Cell[F, CasperState].read
          // Confirm the parents are correct (including checking they commute) and capture
          // the effect needed to compute the correct pre-state as well.
          _ <- Log[F].debug(s"Validating the parents of $hashPrefix")
          merged <- maybeContext.fold(
                     ExecEngineUtil.MergeResult
                       .empty[ExecEngineUtil.TransformMap, Block]
                       .pure[F]
                   ) { ctx =>
                     Validation[F]
                       .parents(block, ctx.genesis.blockHash, dag)
                   }
          _            <- Log[F].debug(s"Computing the pre-state hash of $hashPrefix")
          preStateHash <- ExecEngineUtil.computePrestate[F](merged, block.getHeader.rank, upgrades)
          _            <- Log[F].debug(s"Computing the effects for $hashPrefix")
          blockEffects <- ExecEngineUtil
                           .effectsForBlock[F](block, preStateHash)
                           .recoverWith {
                             case NonFatal(ex) =>
                               Log[F].error(
                                 s"Could not calculate effects for block ${PrettyPrinter
                                   .buildString(block)}: $ex",
                                 ex
                               ) *>
                                 FunctorRaise[F, InvalidBlock].raise(InvalidTransaction)
                           }
          gasSpent = block.getBody.deploys.foldLeft(0L) { case (acc, next) => acc + next.cost }
          _ <- Metrics[F]
                .incrementCounter("gas_spent", gasSpent)
          _ <- Log[F].debug(s"Validating the transactions in $hashPrefix")
          _ <- Validation[F].transactions(
                block,
                preStateHash,
                blockEffects
              )
          _ <- Log[F].debug(s"Validating neglection for $hashPrefix")
          _ <- Validation[F]
                .neglectedInvalidBlock(
                  block,
                  casperState.invalidBlockTracker
                )
          _ <- Log[F].debug(s"Checking equivocation for $hashPrefix")
          _ <- EquivocationDetector
                .checkEquivocationWithUpdate[F](dag, block)
          _ <- Log[F].debug(s"Block effects calculated for $hashPrefix")
        } yield blockEffects).attempt

        validationStatus.flatMap {
          case Right(effects) =>
            addEffects(Valid, block, effects, dag).tupleLeft(Valid)

          case Left(DropErrorWrapper(invalid)) =>
            // These exceptions are coming from the validation checks that used to happen outside attemptAdd,
            // the ones that returned boolean values.
            (invalid: BlockStatus, dag).pure[F]

          case Left(ValidateErrorWrapper(invalid)) =>
            addEffects(invalid, block, Seq.empty, dag)
              .tupleLeft(invalid)

          case Left(unexpected) =>
            for {
              _ <- Log[F].error(
                    s"Unexpected exception during validation of the block ${PrettyPrinter
                      .buildString(block.blockHash)}",
                    unexpected
                  )
              _ <- unexpected.raiseError[F, BlockStatus]
            } yield (UnexpectedBlockException(unexpected), dag)
        }
      }

    // TODO: Handle slashing
    /** Either store the block with its transformation,
      * or add it to the buffer in case the dependencies are missing. */
    def addEffects(
        status: BlockStatus,
        block: Block,
        transforms: Seq[ipc.TransformEntry],
        dag: DagRepresentation[F]
    )(implicit state: Cell[F, CasperState]): F[DagRepresentation[F]] =
      status match {
        //Add successful! Send block to peers, log success, try to add other blocks
        case Valid =>
          for {
            updatedDag <- addToState(block, transforms)
            _ <- Log[F].info(
                  s"Added ${PrettyPrinter.buildString(block.blockHash)}"
                )
          } yield updatedDag

        case MissingBlocks =>
          addMissingDependencies(block, dag) *>
            dag.pure[F]

        case EquivocatedBlock =>
          for {
            updatedDag <- addToState(block, transforms)
            _ <- Log[F].info(
                  s"Added equivocated block ${PrettyPrinter.buildString(block.blockHash)}"
                )
          } yield updatedDag

        case InvalidUnslashableBlock | InvalidBlockNumber | InvalidParents | InvalidSequenceNumber |
            InvalidPrevBlockHash | NeglectedInvalidBlock | InvalidTransaction | InvalidBondsCache |
            InvalidRepeatDeploy | InvalidChainName | InvalidBlockHash | InvalidDeployCount |
            InvalidDeployHash | InvalidDeploySignature | InvalidPreStateHash |
            InvalidPostStateHash | InvalidTargetHash | InvalidDeployHeader |
            InvalidDeployChainName | DeployDependencyNotMet | DeployExpired | DeployFromFuture |
            SwimlaneMerged =>
          handleInvalidBlockEffect(status, block) *> dag.pure[F]

        case Processing | Processed =>
          throw new RuntimeException(s"A block should not be processing at this stage.")

        case UnexpectedBlockException(ex) =>
          Log[F].error(s"Encountered exception in while processing block ${PrettyPrinter
            .buildString(block.blockHash)}: ${ex.getMessage}") *> dag.pure[F]
      }

    /** Remember a block as being invalid, then save it to storage. */
    private def handleInvalidBlockEffect(
        status: BlockStatus,
        block: Block
    )(implicit state: Cell[F, CasperState]): F[Unit] =
      for {
        _ <- Log[F].warn(
              s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for ${status.toString}."
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
        effects: Seq[ipc.TransformEntry]
    ): F[DagRepresentation[F]] =
      for {
        _          <- BlockStorage[F].put(block.blockHash, BlockMsgWithTransform(Some(block), effects))
        updatedDag <- DagStorage[F].getRepresentation
      } yield updatedDag

    /** Check if the block has dependencies that we don't have in store.
      * Add those to the dependency DAG. */
    def addMissingDependencies(
        block: Block,
        dag: DagRepresentation[F]
    )(implicit state: Cell[F, CasperState]): F[Unit] =
      for {
        missingDependencies <- dependenciesHashesOf(block).filterA(dag.contains(_).map(!_))
        _                   <- missingDependencies.traverse(hash => addMissingDependency(hash, block.blockHash))
      } yield ()

    /** Keep track of a block depending on another one, so we know when we can start processing. */
    private def addMissingDependency(
        ancestorHash: BlockHash,
        childHash: BlockHash
    )(implicit state: Cell[F, CasperState]): F[Unit] =
      Cell[F, CasperState].modify(
        s =>
          s.copy(
            dependencyDag = s.dependencyDag.add(ancestorHash, childHash)
          )
      )
  }

  object StatelessExecutor {
    case class Context(genesis: Block, lastFinalizedBlockHash: BlockHash)

    def establishMetrics[F[_]: Metrics]: F[Unit] = {
      implicit val src = CasperMetricsSource
      Metrics[F].incrementCounter("gas_spent", 0L)
    }

    def create[F[_]: MonadThrowable: Time: Log: BlockStorage: DagStorage: ExecutionEngineService: Metrics: DeployStorage: Validation: FinalityDetector: LastFinalizedBlockHashContainer: CasperLabsProtocolVersions: Fs2Compiler](
        chainName: String,
        upgrades: Seq[ipc.ChainSpec.UpgradePoint]
    ): F[StatelessExecutor[F]] =
      establishMetrics[F] as new StatelessExecutor[F](chainName, upgrades)
  }

  /** Encapsulating all methods that might use peer-to-peer communication. */
  trait Broadcaster[F[_]] {
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
    ) = new Broadcaster[F] {

      val maybeOwnPublickKey = validatorId map {
        case ValidatorIdentity(publicKey, _, _) =>
          ByteString.copyFrom(publicKey)
      }

      def networkEffects(
          block: Block,
          status: BlockStatus
      ): F[Unit] =
        status match {
          case Valid | EquivocatedBlock =>
            maybeOwnPublickKey match {
              case Some(key) if key == block.getHeader.validatorPublicKey =>
                relaying.relay(List(block.blockHash)).void
              case _ =>
                // We were adding somebody else's block. The DownloadManager did the gossiping.
                ().pure[F]
            }

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
  }

}
