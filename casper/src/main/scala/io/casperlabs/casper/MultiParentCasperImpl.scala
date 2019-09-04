package io.casperlabs.casper

import cats.data.{EitherT, NonEmptyList}
import cats.effect.concurrent.Semaphore
import cats.effect.{Bracket, Resource, Sync}
import cats.implicits._
import cats.mtl.FunctorRaise
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStorage, DagRepresentation, DagStorage}
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.deploybuffer.DeployBuffer
import io.casperlabs.casper.finality.singlesweep.FinalityDetector
import io.casperlabs.casper.util.ProtoUtil._
import io.casperlabs.casper.util._
import io.casperlabs.casper.util.comm.CommUtil
import io.casperlabs.casper.util.execengine.{DeploysCheckpoint, ExecEngineUtil}
import io.casperlabs.casper.validation.Errors._
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.gossiping
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.transport.TransportLayer
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import io.casperlabs.ipc.ValidateRequest
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform

import scala.util.control.NonFatal

/**
  * Encapsulates mutable state of the MultiParentCasperImpl
  **
  *
  * @param blockBuffer
  * @param invalidBlockTracker
  * @param equivocationsTracker : Used to keep track of when other validators detect the equivocation consisting of the base block at the sequence number identified by the (validator, base equivocation sequence number) pair of each EquivocationRecord.
  */
final case class CasperState(
    blockBuffer: Map[ByteString, Block] = Map.empty,
    invalidBlockTracker: Set[BlockHash] = Set.empty[BlockHash],
    dependencyDag: DoublyLinkedDag[BlockHash] = BlockDependencyDag.empty,
    equivocationsTracker: Set[EquivocationRecord] = Set.empty[EquivocationRecord]
)

class MultiParentCasperImpl[F[_]: Sync: Log: Metrics: Time: FinalityDetector: BlockStorage: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: deploybuffer.DeployBuffer: Validation: Fs2Compiler: DeploySelection](
    statelessExecutor: MultiParentCasperImpl.StatelessExecutor[F],
    broadcaster: MultiParentCasperImpl.Broadcaster[F],
    validatorId: Option[ValidatorIdentity],
    genesis: Block,
    chainId: String,
    blockProcessingLock: Semaphore[F],
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
      Resource
        .make(blockProcessingLock.acquire)(_ => blockProcessingLock.release)
        .use { _ =>
          for {
            dag       <- dag
            blockHash = block.blockHash
            inDag     <- dag.contains(blockHash)
            inBuffer <- Cell[F, CasperState].read
                         .map(casperState => casperState.blockBuffer.contains(blockHash))
            attempts <- if (inDag) {
                         Log[F]
                           .info(
                             s"Block ${PrettyPrinter.buildString(blockHash)} has already been processed by another thread."
                           ) *>
                           List(block -> BlockStatus.processed).pure[F]
                       } else if (inBuffer) {
                         // Waiting for dependencies to become available.
                         Log[F]
                           .info(
                             s"Block ${PrettyPrinter.buildString(blockHash)} is already in the buffer."
                           ) *>
                           List(block -> BlockStatus.processing).pure[F]
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

    Validation[F].preTimestamp(block).attempt.flatMap {
      case Right(None) => addBlock(statelessExecutor.validateAndAddBlock)
      case Right(Some(delay)) =>
        Time[F].sleep(delay) >> Log[F].info(
          s"Block ${PrettyPrinter.buildString(block)} is ahead for $delay from now, will retry adding later"
        ) >> addBlock(statelessExecutor.validateAndAddBlock)
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
      _ <- removeAdded(List(block -> status), canRemove = _ != MissingBlocks)
      furtherAttempts <- status match {
                          case MissingBlocks | IgnorableEquivocation | InvalidUnslashableBlock =>
                            List.empty[(Block, BlockStatus)].pure[F]
                          case _ =>
                            // re-attempt for any status that resulted in the adding of the block into the view
                            reAttemptBuffer(updatedDag, lastFinalizedBlockHash)
                        }
      hashPrefix = PrettyPrinter.buildString(block.blockHash)
      // Update the last finalized block; remove finalized deploys from the buffer
      _         <- Log[F].debug(s"Updating last finalized block after adding ${hashPrefix}")
      _         <- updateLastFinalizedBlock(updatedDag)
      _         <- Log[F].debug(s"Estimating hashes after adding ${hashPrefix}")
      tipHashes <- estimator(updatedDag)
      _ <- Log[F].debug(
            s"Tip estimates: ${tipHashes.map(PrettyPrinter.buildString).mkString(", ")}"
          )
      tipHash = tipHashes.head
      _       <- Log[F].info(s"New fork-choice tip is block ${PrettyPrinter.buildString(tipHash)}.")

      // Push any unfinalized deploys which are still in the buffer back to pending state
      // if the blocks they were contained have just become orphans.
      _        <- Log[F].debug(s"Re-queueing orphaned deploys after adding ${hashPrefix}")
      requeued <- requeueOrphanedDeploys(updatedDag, tipHashes)
      _        <- Log[F].info(s"Re-queued ${requeued} orphaned deploys.").whenA(requeued > 0)

      // Remove any deploys from the buffer which are in finalized blocks.
      _ <- Log[F].debug(s"Removing finalized deploys after adding ${hashPrefix}")
      _ <- removeFinalizedDeploys(updatedDag)
      _ <- Log[F].debug(s"Finished adding ${hashPrefix}")
    } yield (block, status) :: furtherAttempts
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
        deployHashes <- DeployBuffer[F].readProcessedHashes
        blockHashes <- deployHashes
                        .traverse { deployHash =>
                          BlockStorage[F]
                            .findBlockHashesWithDeployhash(deployHash)
                        }
                        .map(_.flatten.distinct)

        finalizedBlockHashes <- blockHashes.filterA(isFinalized(dag, _))

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

  // CON-86 will implement a 2nd pass that will calculate the threshold for secondary parents;
  // right now the FinalityDetector only works for main parents. When it's fixed remove this part.
  private def isFinalized(dag: DagRepresentation[F], blockHash: BlockHash): F[Boolean] =
    isGreaterThanFaultToleranceThreshold(dag, blockHash).ifM(
      true.pure[F],
      dag
        .children(blockHash)
        .flatMap(
          _.toList
            .existsM(isFinalized(dag, _))
        )
    )

  /** Remove deploys from the history which are included in a just finalised block. */
  private def removeDeploysInBlock(blockHash: BlockHash): F[Long] =
    for {
      block              <- ProtoUtil.unsafeGetBlock[F](blockHash)
      deploysToRemove    = block.body.get.deploys.map(_.deploy.get).toList
      initialHistorySize <- DeployBuffer[F].sizePendingOrProcessed()
      _                  <- DeployBuffer[F].markAsFinalized(deploysToRemove)
      deploysRemoved <- DeployBuffer[F]
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
      faultTolerance <- FinalityDetector[F].normalizedFaultTolerance(dag, blockHash)
      _ <- Log[F].trace(
            s"Fault tolerance for block ${PrettyPrinter.buildString(blockHash)} is $faultTolerance; threshold is $faultToleranceThreshold"
          )
    } yield faultTolerance > faultToleranceThreshold

  /** Check that either we have the block already scheduled but missing dependencies, or it's in the store */
  def contains(
      block: Block
  ): F[Boolean] =
    Cell[F, CasperState].read
      .map(_.blockBuffer.contains(block.blockHash))
      .ifM(
        true.pure[F],
        BlockStorage[F].contains(block.blockHash)
      )

  /** Add a deploy to the buffer, if the code passes basic validation. */
  def deploy(deploy: Deploy): F[Either[Throwable, Unit]] = validatorId match {
    case Some(_) =>
      (deploy.getBody.session, deploy.getBody.payment) match {
        case (None, _) | (_, None) | (Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty)), _) |
            (_, Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty))) =>
          new IllegalArgumentException(s"Deploy was missing session and/or payment code.")
            .asLeft[Unit]
            .pure[F]
            .widen

        case (Some(session), Some(payment)) =>
          val req: F[Either[String, Unit]] =
            if (session.contract.isWasm && payment.contract.isWasm)
              ExecutionEngineService[F]
                .verifyWasm(ValidateRequest(session.getWasm, payment.getWasm))
            else
              Log[F]
                .warn(
                  s"Cannot verify contracts unless both are Wasm. ${session.contract.getClass} & ${payment.contract.getClass()}"
                )
                .as(().asRight[String])

          EitherT(req)
            .leftMap(c => new IllegalArgumentException(s"Contract verification failed: $c"))
            .flatMapF(_ => addDeploy(deploy))
            .value
      }
    case None =>
      new IllegalStateException(s"Node is in read-only mode.").asLeft[Unit].pure[F].widen
  }

  /** Add a deploy to the buffer, to be executed later. */
  private def addDeploy(deploy: Deploy): F[Either[Throwable, Unit]] = {
    def show(d: Deploy) = PrettyPrinter.buildString(d)
    (for {
      processedDeploys <- DeployBuffer[F].readProcessedByAccount(deploy.getHeader.accountPublicKey)
      _ <- processedDeploys
            .find { d =>
              d.getHeader.nonce >= deploy.getHeader.nonce &&
              d.deployHash != deploy.deployHash
            }
            .map { d =>
              new IllegalArgumentException(s"${show(d)} supersedes ${show(deploy)}.")
                .raiseError[F, Unit]
            } getOrElse ().pure[F]
      _ <- DeployBuffer[F].addAsPending(List(deploy))
      _ <- Log[F].info(s"Received ${show(deploy)}")
    } yield ()).attempt
  }

  /** Return the list of tips. */
  def estimator(dag: DagRepresentation[F]): F[IndexedSeq[BlockHash]] =
    Metrics[F].timer("estimator") {
      Estimator.tips[F](dag, genesis.blockHash)
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
          dag       <- dag
          tipHashes <- estimator(dag).map(_.toVector)
          tips      <- tipHashes.traverse(ProtoUtil.unsafeGetBlock[F])
          merged    <- ExecEngineUtil.merge[F](tips, dag)
          parents   = merged.parents
          _ <- Log[F].info(
                s"${parents.size} parents out of ${tipHashes.size} latest blocks will be used."
              )
          remainingHashes  <- remainingDeploysHashes(dag, parents) // TODO: Should be streaming all the way down
          bondedValidators = bonds(parents.head).map(_.validatorPublicKey).toSet
          //We ensure that only the justifications given in the block are those
          //which are bonded validators in the chosen parent. This is safe because
          //any latest message not from a bonded validator will not change the
          //final fork-choice.
          latestMessages   <- dag.latestMessages
          bondedLatestMsgs = latestMessages.filter { case (v, _) => bondedValidators.contains(v) }
          justifications   = toJustification(bondedLatestMsgs)
          rank             = ProtoUtil.calculateRank(bondedLatestMsgs.values.toSeq)
          protocolVersion  = CasperLabsProtocolVersions.thresholdsVersionMap.versionAt(rank)
          proposal <- if (remainingHashes.nonEmpty || parents.length > 1) {
                       createProposal(
                         parents,
                         merged,
                         remainingHashes,
                         justifications,
                         protocolVersion
                       )
                     } else {
                       CreateBlockStatus.noNewDeploys.pure[F]
                     }
          signedBlock <- proposal match {
                          case Created(block) =>
                            signBlock[F](block, dag, publicKey, privateKey, sigAlgorithm)
                              .map(Created.apply(_): CreateBlockStatus)
                          case _ => proposal.pure[F]
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
      parents: Seq[Block]
  ): F[Set[DeployHash]] = Metrics[F].timer("remainingDeploys") {
    val candidateBlockHashesF = for {
      // We have re-queued orphan deploys already, so we can just look at pending ones.
      pendingDeployHashes <- DeployBuffer[F].readPendingHashes

      // Make sure pending deploys have never been processed in the past cone of the new parents.
      candidateBlockHashes <- filterDeploysNotInPast(dag, parents, pendingDeployHashes)

      // The ones which aren't candidates now can be discarded as duplicates.
      deploysToDiscard = pendingDeployHashes.toSet -- candidateBlockHashes
      _ <- DeployBuffer[F]
            .markAsDiscardedByHashes(deploysToDiscard.toList)
            .whenA(deploysToDiscard.nonEmpty)
    } yield candidateBlockHashes.toSet

    (for {
      candidateBlockHashes <- fs2.Stream.eval(candidateBlockHashesF)
      // Only send the next nonce per account. This will change once the nonce check is removed in the EE
      // and support for SEQ/PAR blocks is added, then we can send all deploys for the account.
      remainingHashes <- DeployBuffer[F]
                          .readAccountLowestNonce()
                          .filter(candidateBlockHashes.contains(_))
    } yield remainingHashes).compile.to[Set]
  }

  /** If another node proposed a block which orphaned something proposed by this node,
    * and we still have these deploys in the `processedDeploys` buffer then put them
    * back into the `pendingDeploys` so that the `AutoProposer` can pick them up again.
    */
  private def requeueOrphanedDeploys(
      dag: DagRepresentation[F],
      tipHashes: IndexedSeq[BlockHash]
  ): F[Int] = Metrics[F].timer("requeueOrphanedDeploys") {
    for {
      // We actually need the tips which can be merged, the ones which we'd build on if we
      // attempted to create a new block.
      tips    <- tipHashes.toList.traverse(ProtoUtil.unsafeGetBlock[F])
      merged  <- ExecEngineUtil.merge[F](tips, dag)
      parents = merged.parents
      // Consider deploys which this node has processed but hasn't finalized yet.
      processedDeploys <- DeployBuffer[F].readProcessedHashes
      orphanedDeploys  <- filterDeploysNotInPast(dag, parents, processedDeploys)
      _                <- DeployBuffer[F].markAsPendingByHashes(orphanedDeploys) whenA orphanedDeploys.nonEmpty
    } yield orphanedDeploys.size
  }

  /** Find deploys which either haven't been processed yet or are in blocks which are
    * not in the past cone of the chosen parents.
    */
  private def filterDeploysNotInPast(
      dag: DagRepresentation[F],
      parents: Seq[Block],
      deployHashes: List[ByteString]
  ): F[List[ByteString]] =
    for {
      deployHashToBlocksMap <- deployHashes
                                .traverse { deployHash =>
                                  BlockStorage[F]
                                    .findBlockHashesWithDeployhash(deployHash)
                                    .map(deployHash -> _)
                                }
                                .map(_.toMap)

      blockHashes = deployHashToBlocksMap.values.flatten.toList.distinct

      // Find the blocks from which there's a way through the descendants to reach a tip.
      parentSet = parents.map(_.blockHash).toSet
      nonOrphanedBlockHashes <- DagOperations
                                 .collectWhereDescendantPathExists[F](
                                   dag,
                                   blockHashes.toSet,
                                   parentSet
                                 )

      deploysNotInPast = deployHashToBlocksMap.collect {
        case (deployHash, blockHashes)
            if blockHashes.isEmpty || !blockHashes.exists(nonOrphanedBlockHashes) =>
          deployHash
      }.toList

    } yield deploysNotInPast

  //TODO: Need to specify SEQ vs PAR type block?
  /** Execute a set of deploys in the context of chosen parents. Compile them into a block if everything goes fine. */
  private def createProposal(
      parents: Seq[Block], // TODO: We only need their hashes
      merged: ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block],
      deploys: Set[DeployHash],
      justifications: Seq[Justification],
      protocolVersion: ProtocolVersion
  ): F[CreateBlockStatus] = Metrics[F].timer("createProposal") {
    (for {
      now <- Time[F].currentMillis
      result <- ExecEngineUtil
                 .computeDeploysCheckpoint[F](
                   merged,
                   deploys,
                   now,
                   protocolVersion
                 )
      DeploysCheckpoint(
        preStateHash,
        postStateHash,
        bondedValidators,
        deploysForBlock,
        protocolVersion
      )                 = result
      dag               <- dag
      justificationMsgs <- justifications.toList.traverse(j => dag.lookup(j.latestBlockHash))
      rank              = ProtoUtil.calculateRank(justificationMsgs.flatten)
      status = if (deploysForBlock.isEmpty) {
        CreateBlockStatus.noNewDeploys
      } else {

        val postState = Block
          .GlobalState()
          .withPreStateHash(preStateHash)
          .withPostStateHash(postStateHash)
          .withBonds(bondedValidators)

        val body = Block
          .Body()
          .withDeploys(deploysForBlock)

        val header = blockHeader(
          body,
          parentHashes = parents.map(_.blockHash),
          justifications = justifications,
          state = postState,
          rank = rank,
          protocolVersion = protocolVersion.value,
          timestamp = now,
          chainId = chainId
        )
        val block = unsignedBlockProto(body, header)

        CreateBlockStatus.created(block)
      }
    } yield status)
      .handleErrorWith {
        case ex @ SmartContractEngineError(error_msg) =>
          Log[F]
            .error(s"Execution Engine returned an error while processing deploys: $error_msg")
            .as(CreateBlockStatus.internalDeployError(ex))
        case NonFatal(ex) =>
          Log[F]
            .error(s"Critical error encountered while processing deploys: ${ex.getMessage}", ex)
            .as(CreateBlockStatus.internalDeployError(ex))
      }
  }

  // MultiParentCasper Exposes the block DAG to those who need it.
  def dag: F[DagRepresentation[F]] =
    DagStorage[F].getRepresentation

  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    for {
      state   <- Cell[F, CasperState].read
      tracker = state.equivocationsTracker
    } yield tracker
      .map(_.equivocator)
      .flatMap(weights.get)
      .sum
      .toFloat / weightMapTotal(weights)

  /** After a block is executed we can try to execute the other blocks in the buffer that dependent on it. */
  private def reAttemptBuffer(
      dag: DagRepresentation[F],
      lastFinalizedBlockHash: BlockHash
  ): F[List[(Block, BlockStatus)]] =
    for {
      casperState    <- Cell[F, CasperState].read
      dependencyFree = casperState.dependencyDag.dependencyFree
      dependencyFreeBlocks = casperState.blockBuffer.values
        .filter(block => dependencyFree(block.blockHash))
        .toList
      (attempts, updatedDag) <- dependencyFreeBlocks.foldM((List.empty[(Block, BlockStatus)], dag)) {
                                 case ((attempts, updatedDag), block) =>
                                   for {
                                     (status, updatedDag) <- statelessExecutor.validateAndAddBlock(
                                                              StatelessExecutor
                                                                .Context(
                                                                  genesis,
                                                                  lastFinalizedBlockHash
                                                                )
                                                                .some,
                                                              updatedDag,
                                                              block
                                                            )
                                   } yield ((block, status) :: attempts, updatedDag)
                               }
      furtherAttempts <- if (attempts.isEmpty) List.empty[(Block, BlockStatus)].pure[F]
                        else {
                          removeAdded(attempts, canRemove = _.inDag) *>
                            reAttemptBuffer(updatedDag, lastFinalizedBlockHash)
                        }
    } yield attempts ++ furtherAttempts

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

    DeployBuffer[F].markAsProcessed(processedDeploys) >>
      Cell[F, CasperState].modify { s =>
        s.copy(
          blockBuffer = s.blockBuffer.filterNot(kv => addedBlockHashes(kv._1)),
          dependencyDag = addedBlocks.foldLeft(s.dependencyDag) {
            case (dag, block) =>
              dag.remove(block.blockHash)
          }
        )
      }
  }

  /** Called periodically from outside to ask all peers again
    * to send us blocks for which we are missing some dependencies. */
  def fetchDependencies: F[Unit] =
    for {
      s <- Cell[F, CasperState].read
      _ <- s.dependencyDag.dependencyFree.toList.traverse(broadcaster.requestMissingDependency)
    } yield ()

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

  def create[F[_]: Sync: Log: Metrics: Time: FinalityDetector: BlockStorage: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: DeployBuffer: Validation: Cell[
    ?[_],
    CasperState
  ]: DeploySelection](
      statelessExecutor: StatelessExecutor[F],
      broadcaster: Broadcaster[F],
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      chainId: String,
      blockProcessingLock: Semaphore[F],
      faultToleranceThreshold: Float = 0f
  ): F[MultiParentCasper[F]] =
    LastFinalizedBlockHashContainer[F].set(genesis.blockHash) >>
      Sync[F].delay(
        new MultiParentCasperImpl[F](
          statelessExecutor,
          broadcaster,
          validatorId,
          genesis,
          chainId,
          blockProcessingLock,
          faultToleranceThreshold
        )
      )

  /** Component purely to validate, execute and store blocks.
    * Even the Genesis, to create it in the first place. */
  class StatelessExecutor[F[_]: MonadThrowable: Time: Log: BlockStorage: DagStorage: ExecutionEngineService: Metrics: DeployBuffer: Validation: FinalityDetector: LastFinalizedBlockHashContainer](
      chainId: String
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
                chainId,
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
          preStateHash <- ExecEngineUtil.computePrestate[F](merged)
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
          _ <- maybeContext.fold(().pure[F]) { ctx =>
                EquivocationDetector
                  .checkNeglectedEquivocationsWithUpdate[F](
                    block,
                    ctx.genesis
                  )
              }
          _ <- Log[F].debug(s"Validating neglection for $hashPrefix")
          _ <- Validation[F]
                .neglectedInvalidBlock(
                  block,
                  casperState.invalidBlockTracker
                )
          _ <- Log[F].debug(s"Checking equivocation for $hashPrefix")
          _ <- EquivocationDetector.checkEquivocations[F](casperState.dependencyDag, block, dag)
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
          Cell[F, CasperState].modify { s =>
            s.copy(blockBuffer = s.blockBuffer + (block.blockHash -> block))
          } *>
            addMissingDependencies(block, dag) *>
            dag.pure[F]

        case AdmissibleEquivocation =>
          val baseEquivocationBlockSeqNum = block.getHeader.validatorBlockSeqNum - 1
          for {
            _ <- Cell[F, CasperState].modify { s =>
                  if (s.equivocationsTracker.exists {
                        case EquivocationRecord(validator, seqNum, _) =>
                          block.getHeader.validatorPublicKey == validator && baseEquivocationBlockSeqNum == seqNum
                      }) {
                    // More than 2 equivocating children from base equivocation block and base block has already been recorded
                    s
                  } else {
                    val newEquivocationRecord =
                      EquivocationRecord(
                        block.getHeader.validatorPublicKey,
                        baseEquivocationBlockSeqNum,
                        Set.empty[BlockHash]
                      )
                    s.copy(equivocationsTracker = s.equivocationsTracker + newEquivocationRecord)
                  }
                }
            updatedDag <- addToState(block, transforms)
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
              s"Did not add block ${PrettyPrinter.buildString(block.blockHash)} as that would add an equivocation to the DAG"
            ) *> dag.pure[F]

        case InvalidUnslashableBlock | InvalidBlockNumber | InvalidParents | InvalidSequenceNumber |
            NeglectedInvalidBlock | NeglectedEquivocation | InvalidTransaction | InvalidBondsCache |
            InvalidRepeatDeploy | InvalidChainId | InvalidBlockHash | InvalidDeployCount |
            InvalidDeployHash | InvalidDeploySignature | InvalidPreStateHash |
            InvalidPostStateHash =>
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
        updatedDag <- DagStorage[F].insert(block)
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

    def create[F[_]: MonadThrowable: Time: Log: BlockStorage: DagStorage: ExecutionEngineService: Metrics: DeployBuffer: Validation: FinalityDetector: LastFinalizedBlockHashContainer](
        chainId: String
    ): F[StatelessExecutor[F]] =
      for {
        _ <- establishMetrics[F]
      } yield new StatelessExecutor[F](chainId)
  }

  /** Encapsulating all methods that might use peer-to-peer communication. */
  trait Broadcaster[F[_]] {
    def networkEffects(
        block: Block,
        status: BlockStatus
    ): F[Unit]

    def requestMissingDependency(blockHash: BlockHash): F[Unit]
  }

  object Broadcaster {
    def fromTransportLayer[F[_]: Monad: ConnectionsCell: TransportLayer: Log: Time: ErrorHandler: RPConfAsk]()(
        implicit state: Cell[F, CasperState]
    ) =
      new Broadcaster[F] {

        /** Gossip the created block, or ask for dependencies. */
        def networkEffects(
            block: Block, // Not just BlockHash because if the status MissingBlocks it's not in store yet; although we should be able to get it from CasperState.
            status: BlockStatus
        ): F[Unit] =
          status match {
            //Add successful! Send block to peers.
            case Valid | AdmissibleEquivocation =>
              CommUtil.sendBlock[F](LegacyConversions.fromBlock(block))

            case MissingBlocks =>
              // In the future this won't happen because the DownloadManager won't try to add blocks with missing dependencies.
              fetchMissingDependencies(block)

            case IgnorableEquivocation | InvalidUnslashableBlock | InvalidBlockNumber |
                InvalidParents | InvalidSequenceNumber | NeglectedInvalidBlock |
                NeglectedEquivocation | InvalidTransaction | InvalidBondsCache |
                InvalidRepeatDeploy | InvalidChainId | InvalidBlockHash | InvalidDeployCount |
                InvalidDeployHash | InvalidDeploySignature | InvalidPreStateHash |
                InvalidPostStateHash | Processing | Processed =>
              Log[F].debug(
                s"Not sending notification about ${PrettyPrinter.buildString(block.blockHash)}: $status"
              )

            case UnexpectedBlockException(ex) =>
              Log[F].debug(
                s"Not sending notification about ${PrettyPrinter.buildString(block.blockHash)}: $ex"
              )
          }

        /** Ask all peers to send us a block. */
        def requestMissingDependency(blockHash: BlockHash) =
          CommUtil.sendBlockRequest[F](
            protocol.BlockRequest(Base16.encode(blockHash.toByteArray), blockHash)
          )

        /** Check if the block has dependencies that we don't have in store of buffer.
          * Add those to the dependency DAG and ask peers to send it. */
        private def fetchMissingDependencies(
            block: Block
        )(implicit state: Cell[F, CasperState]): F[Unit] =
          for {
            casperState <- Cell[F, CasperState].read
            missingDependencies = casperState.dependencyDag
              .childToParentAdjacencyList(block.blockHash)
              .toList
            // NOTE: Requesting not just unseen dependencies so that something that was originally
            // an `IgnorableEquivocation` can second time be fetched again and be an `AdmissibleEquivocation`.
            _ <- missingDependencies.traverse(hash => requestMissingDependency(hash))
          } yield ()
      }

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
          case Valid | AdmissibleEquivocation =>
            maybeOwnPublickKey match {
              case Some(key) if key == block.getHeader.validatorPublicKey =>
                relaying.relay(List(block.blockHash))
              case _ =>
                // We were adding somebody else's block. The DownloadManager did the gossiping.
                ().pure[F]
            }

          case MissingBlocks =>
            throw new RuntimeException(
              "Impossible! The DownloadManager should not tell Casper about blocks with missing dependencies!"
            )

          case IgnorableEquivocation | InvalidUnslashableBlock | InvalidBlockNumber |
              InvalidParents | InvalidSequenceNumber | NeglectedInvalidBlock |
              NeglectedEquivocation | InvalidTransaction | InvalidBondsCache | InvalidRepeatDeploy |
              InvalidChainId | InvalidBlockHash | InvalidDeployCount | InvalidDeployHash |
              InvalidDeploySignature | InvalidPreStateHash | InvalidPostStateHash | Processing |
              Processed =>
            ().pure[F]

          case UnexpectedBlockException(_) =>
            ().pure[F]
        }

      def requestMissingDependency(blockHash: BlockHash): F[Unit] =
        // We are letting Casper know about the pending DAG, so it may try to ask for dependencies,
        // but those will be naturally downloaded by the DownloadManager.
        ().pure[F]
    }
  }

}
