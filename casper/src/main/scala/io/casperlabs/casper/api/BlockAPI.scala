package io.casperlabs.casper.api

import cats.Monad
import cats.effect.concurrent.Semaphore
import cats.effect.{Bracket, Concurrent, Resource}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore, StorageError}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info._
import io.casperlabs.casper.protocol.{
  BlockInfoWithoutTuplespace,
  BlockQuery,
  BlockQueryResponse,
  DeployServiceResponse,
  BlockInfo => BlockInfoWithTuplespace
}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.{protocol, BlockStatus => _, _}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.ServiceError
import io.casperlabs.comm.ServiceError._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log

object BlockAPI {

  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "block-api")

  private def unsafeWithCasper[F[_]: MonadThrowable: Log: MultiParentCasperRef, A](
      msg: String
  )(f: MultiParentCasper[F] => F[A]): F[A] =
    MultiParentCasperRef
      .withCasper[F, A](
        f,
        msg,
        MonadThrowable[F].raiseError(Unavailable("Casper instance not available yet."))
      )

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("deploys", 0)
      _ <- Metrics[F].incrementCounter("deploys-success", 0)
      _ <- Metrics[F].incrementCounter("create-blocks", 0)
      _ <- Metrics[F].incrementCounter("create-blocks-success", 0)
    } yield ()

  @deprecated("To be removed before devnet. Use the one with `Deploy`.", "0.4")
  def deploy[F[_]: MonadThrowable: MultiParentCasperRef: Log: Metrics](
      d: protocol.DeployData,
      ignoreDeploySignature: Boolean
  ): F[DeployServiceResponse] = {
    def casperDeploy(implicit casper: MultiParentCasper[F]): F[DeployServiceResponse] =
      for {
        _ <- Metrics[F].incrementCounter("deploys")
        _ <- MonadThrowable[F]
              .raiseError {
                Unimplemented(
                  "Signature check on protocol.DeployData is not implemented. Use CasperService."
                )
              }
              .whenA(!ignoreDeploySignature)
        n = LegacyConversions.toDeploy(d)
        r <- MultiParentCasper[F].deploy(n)
        re <- r match {
               case Right(_) =>
                 Metrics[F].incrementCounter("deploys-success") *>
                   DeployServiceResponse(success = true, "Success!").pure[F]
               case Left(err) =>
                 DeployServiceResponse(success = false, err.getMessage).pure[F]
             }
      } yield re

    val errorMessage = "Could not deploy."

    MultiParentCasperRef
      .withCasper[F, DeployServiceResponse](
        casperDeploy(_),
        errorMessage,
        DeployServiceResponse(success = false, s"Error: $errorMessage").pure[F]
      )
  }

  def deploy[F[_]: MonadThrowable: MultiParentCasperRef: BlockStore: SafetyOracle: Log: Metrics](
      d: Deploy,
      ignoreDeploySignature: Boolean
  ): F[Unit] = unsafeWithCasper[F, Unit]("Could not deploy.") { implicit casper =>
    def check(msg: String)(f: F[Boolean]): F[Unit] =
      f flatMap { ok =>
        MonadThrowable[F].raiseError(InvalidArgument(msg)).whenA(!ok)
      }

    for {
      _ <- Metrics[F].incrementCounter("deploys")
      // Doing these here while MultiParentCasper is still using the legacy deploys.
      _ <- check("Invalid deploy hash.")(Validate.deployHash[F](d))
      _ <- check("Invalid deploy signature.")(Validate.deploySignature[F](d))
            .whenA(!ignoreDeploySignature)

      t = casper.faultToleranceThreshold
      _ <- ensureNotInDag[F](d, t)

      r <- MultiParentCasper[F].deploy(d)
      _ <- r match {
            case Right(_) =>
              Metrics[F].incrementCounter("deploys-success") *> ().pure[F]
            case Left(ex: IllegalArgumentException) =>
              MonadThrowable[F].raiseError[Unit](InvalidArgument(ex.getMessage))
            case Left(ex) =>
              MonadThrowable[F].raiseError[Unit](ex)
          }
    } yield ()
  }

  /** Check that we don't have this deploy already in the finalized part of the DAG. */
  private def ensureNotInDag[F[_]: MonadThrowable: MultiParentCasperRef: BlockStore: SafetyOracle: Log](
      d: Deploy,
      faultToleranceThreshold: Float
  ): F[Unit] =
    BlockStore[F]
      .findBlockHashesWithDeployhash(d.deployHash)
      .flatMap(
        _.toList.traverse(blockHash => getBlockInfo[F](Base16.encode(blockHash.toByteArray)))
      )
      .flatMap {
        case Nil =>
          ().pure[F]
        case infos =>
          infos.find(_.getStatus.faultTolerance > faultToleranceThreshold).fold(().pure[F]) {
            finalized =>
              MonadThrowable[F].raiseError {
                AlreadyExists(
                  s"Block ${PrettyPrinter.buildString(finalized.getSummary.blockHash)} with fault tolerance ${finalized.getStatus.faultTolerance} already contains ${PrettyPrinter
                    .buildString(d)}"
                )
              }
          }
      }

  @deprecated("To be removed before devnet. Use `propose`.", "0.4")
  def createBlock[F[_]: Concurrent: MultiParentCasperRef: Log: Metrics](
      blockApiLock: Semaphore[F]
  ): F[DeployServiceResponse] =
    propose(blockApiLock) map { blockHash =>
      val hash = PrettyPrinter.buildString(blockHash)
      DeployServiceResponse(success = true, s"Success! Block $hash created and added.")
    } handleError {
      case InvalidArgument(msg) =>
        DeployServiceResponse(success = false, s"Failure! $msg")
      case Internal(msg) =>
        DeployServiceResponse(success = false, msg)
      case Aborted(msg) =>
        DeployServiceResponse(success = false, s"Error: $msg")
      case FailedPrecondition(msg) =>
        DeployServiceResponse(success = false, s"Error while creating block: $msg")
      case OutOfRange(msg) =>
        DeployServiceResponse(success = false, s"Error while creating block: $msg")
      case Unavailable(_) =>
        DeployServiceResponse(success = false, s"Error: Could not create block.")
    }

  def propose[F[_]: Bracket[?[_], Throwable]: MultiParentCasperRef: Log: Metrics](
      blockApiLock: Semaphore[F]
  ): F[ByteString] = {
    def raise[A](ex: ServiceError.Exception): F[ByteString] =
      MonadThrowable[F].raiseError(ex)

    unsafeWithCasper[F, ByteString]("Could not create block.") { implicit casper =>
      Resource.make(blockApiLock.tryAcquire)(blockApiLock.release.whenA).use {
        case true =>
          for {
            _          <- Metrics[F].incrementCounter("create-blocks")
            maybeBlock <- casper.createBlock
            result <- maybeBlock match {
                       case Created(block) =>
                         for {
                           status <- casper.addBlock(block)
                           res <- status match {
                                   case _: ValidBlock =>
                                     block.blockHash.pure[F]
                                   case _: InvalidBlock =>
                                     raise(InvalidArgument(s"Invalid block: $status"))
                                   case BlockException(ex) =>
                                     raise(Internal(s"Error during block processing: $ex"))
                                   case Processing | Processed =>
                                     raise(
                                       Aborted(
                                         "No action taken since other thread is already processing the block."
                                       )
                                     )
                                 }
                           _ <- Metrics[F].incrementCounter("create-blocks-success")
                         } yield res

                       case InternalDeployError(ex) =>
                         raise(Internal(ex.getMessage))

                       case ReadOnlyMode =>
                         raise(FailedPrecondition("Node is in read-only mode."))

                       case NoNewDeploys =>
                         raise(OutOfRange("No new deploys."))
                     }
          } yield result

        case false =>
          raise(Aborted("There is another propose in progress."))
      }
    }
  }

  // FIX: Not used at the moment - in RChain it's being used in method like `getListeningName*`
  @deprecated("To be removed before devnet.", "0.4")
  private def getMainChainFromTip[F[_]: MonadThrowable: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[IndexedSeq[Block]] =
    for {
      dag       <- MultiParentCasper[F].blockDag
      tipHashes <- MultiParentCasper[F].estimator(dag)
      tipHash   = tipHashes.head
      tip       <- ProtoUtil.unsafeGetBlock[F](tipHash)
      mainChain <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[Block], depth)
    } yield mainChain

  // TOOD extract common code from show blocks
  @deprecated("To be removed before devnet. Use `getBlockInfos`.", "0.4")
  def showBlocks[F[_]: MonadThrowable: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    val errorMessage =
      "Could not show blocks."

    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag <- MultiParentCasper[F].blockDag
        flattenedBlockInfosUntilDepth <- getFlattenedBlockInfosUntilDepth[F](
                                          depth,
                                          dag
                                        )
      } yield flattenedBlockInfosUntilDepth.reverse

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      errorMessage,
      List.empty[BlockInfoWithoutTuplespace].pure[F]
    )
  }

  private def getFlattenedBlockInfosUntilDepth[F[_]: MonadThrowable: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int,
      dag: BlockDagRepresentation[F]
  ): F[List[BlockInfoWithoutTuplespace]] =
    for {
      topoSort <- dag.topoSortTail(depth)
      result <- topoSort.foldM(List.empty[BlockInfoWithoutTuplespace]) {
                 case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
                   for {
                     blocksAtHeight     <- blockHashesAtHeight.traverse(ProtoUtil.unsafeGetBlock[F])
                     blockInfosAtHeight <- blocksAtHeight.traverse(getBlockInfoWithoutTuplespace[F])
                   } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
               }
    } yield result

  @deprecated("To be removed before devnet.", "0.4")
  def showMainChain[F[_]: MonadThrowable: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    val errorMessage =
      "Could not show main chain."

    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        tipHashes  <- MultiParentCasper[F].estimator(dag)
        tipHash    = tipHashes.head
        tip        <- ProtoUtil.unsafeGetBlock[F](tipHash)
        mainChain  <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[Block], depth)
        blockInfos <- mainChain.toList.traverse(getBlockInfoWithoutTuplespace[F])
      } yield blockInfos

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      errorMessage,
      List.empty[BlockInfoWithoutTuplespace].pure[F]
    )
  }

  // TODO: Replace with call to BlockStore
  @deprecated("To be removed before devnet. Will add `getDeployInfo`.", "0.4")
  def findBlockWithDeploy[F[_]: MonadThrowable: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      accountPublicKey: ByteString,
      timestamp: Long
  ): F[BlockQueryResponse] = {
    val errorMessage =
      "Could not find block with deploy."

    def casperResponse(implicit casper: MultiParentCasper[F]): F[BlockQueryResponse] =
      for {
        dag               <- MultiParentCasper[F].blockDag
        allBlocksTopoSort <- dag.topoSort(0L)
        maybeBlock <- findBlockWithDeploy[F](
                       allBlocksTopoSort.flatten.reverse,
                       accountPublicKey,
                       timestamp
                     )
        blockQueryResponse <- maybeBlock.traverse(getFullBlockInfo[F])
      } yield blockQueryResponse.fold(
        BlockQueryResponse(
          status = s"Error: Failure to find block containing deploy signed by ${PrettyPrinter
            .buildString(accountPublicKey)} with timestamp ${timestamp.toString}"
        )
      )(
        blockInfo =>
          BlockQueryResponse(
            status = "Success",
            blockInfo = Some(blockInfo)
          )
      )

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      errorMessage,
      BlockQueryResponse(status = s"Error: errorMessage").pure[F]
    )
  }

  private def findBlockWithDeploy[F[_]: MonadThrowable: Log: BlockStore](
      blockHashes: Vector[BlockHash],
      accountPublicKey: ByteString,
      timestamp: Long
  ): F[Option[Block]] =
    blockHashes.toStream
      .traverse(ProtoUtil.unsafeGetBlock[F](_))
      .map(blocks => blocks.find(ProtoUtil.containsDeploy(_, accountPublicKey, timestamp)))

  def getDeployInfoOpt[F[_]: MonadThrowable: Log: MultiParentCasperRef: SafetyOracle: BlockStore](
      deployHashBase16: String
  ): F[Option[DeployInfo]] =
    // LMDB throws an exception if a key isn't 32 bytes long, so we fail-fast here
    if (deployHashBase16.length != 64) {
      Log[F].warn("Deploy hash must be 32 bytes long") >> none[DeployInfo].pure[F]
    } else {
      unsafeWithCasper[F, Option[DeployInfo]]("Could not show deploy.") { implicit casper =>
        val deployHash = ByteString.copyFrom(Base16.decode(deployHashBase16))

        BlockStore[F].findBlockHashesWithDeployhash(deployHash) flatMap {
          case blockHashes if blockHashes.nonEmpty =>
            for {
              blocks <- blockHashes.toList.traverse(ProtoUtil.unsafeGetBlock[F](_))
              blockInfos <- blocks.traverse { block =>
                             val summary =
                               BlockSummary(block.blockHash, block.header, block.signature)
                             makeBlockInfo[F](summary, block.some)
                           }
              results = (blocks zip blockInfos).flatMap {
                case (block, info) =>
                  block.getBody.deploys
                    .find(_.getDeploy.deployHash == deployHash)
                    .map(_ -> info)
              }
              info = DeployInfo(
                deploy = results.headOption.flatMap(_._1.deploy),
                processingResults = results.map {
                  case (processedDeploy, blockInfo) =>
                    DeployInfo
                      .ProcessingResult(
                        cost = processedDeploy.cost,
                        isError = processedDeploy.isError,
                        errorMessage = processedDeploy.errorMessage
                      )
                      .withBlockInfo(blockInfo)
                }
              )
            } yield info.some

          case _ =>
            casper.bufferedDeploys.map { deploys =>
              deploys.get(deployHash) map { deploy =>
                DeployInfo().withDeploy(deploy)
              }
            }
        }
      }
    }

  def getDeployInfo[F[_]: MonadThrowable: Log: MultiParentCasperRef: SafetyOracle: BlockStore](
      deployHashBase16: String
  ): F[DeployInfo] =
    getDeployInfoOpt[F](deployHashBase16).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[DeployInfo](NotFound(s"Cannot find deploy with hash $deployHashBase16"))
      )(_.pure[F])
    )

  def getBlockDeploys[F[_]: MonadThrowable: Log: MultiParentCasperRef: BlockStore](
      blockHashBase16: String
  ): F[Seq[Block.ProcessedDeploy]] =
    unsafeWithCasper[F, Seq[Block.ProcessedDeploy]]("Could not show deploys.") { implicit casper =>
      getByHashPrefix(blockHashBase16) {
        ProtoUtil.unsafeGetBlock[F](_).map(_.some)
      }.map(_.get.getBody.deploys)
    }

  def makeBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle](
      summary: BlockSummary,
      maybeBlock: Option[Block]
  ): F[BlockInfo] =
    for {
      dag            <- MultiParentCasper[F].blockDag
      faultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, summary.blockHash)
      initialFault <- MultiParentCasper[F].normalizedInitialFault(
                       ProtoUtil.weightMap(summary.getHeader)
                     )
      maybeStats = maybeBlock.map { block =>
        BlockStatus
          .Stats()
          .withBlockSizeBytes(block.serializedSize)
          .withDeployErrorCount(
            block.getBody.deploys.count(_.isError)
          )
      }
      status = BlockStatus(
        faultTolerance = faultTolerance - initialFault,
        stats = maybeStats
      )
      info = BlockInfo()
        .withSummary(summary)
        .withStatus(status)
    } yield info

  def makeBlockInfo[F[_]: Monad: BlockStore: MultiParentCasper: SafetyOracle](
      summary: BlockSummary,
      full: Boolean
  ): F[(BlockInfo, Option[Block])] =
    for {
      maybeBlock <- if (full) {
                     BlockStore[F]
                       .get(summary.blockHash)
                       .map(_.get.blockMessage)
                   } else {
                     none[Block].pure[F]
                   }
      info <- makeBlockInfo[F](summary, maybeBlock)
    } yield (info, maybeBlock)

  def getBlockInfoWithBlock[F[_]: MonadThrowable: Log: MultiParentCasperRef: SafetyOracle: BlockStore](
      blockHash: BlockHash,
      full: Boolean = false
  ): F[(BlockInfo, Option[Block])] =
    unsafeWithCasper[F, (BlockInfo, Option[Block])]("Could not show block.") { implicit casper =>
      BlockStore[F].getBlockSummary(blockHash).flatMap { maybeSummary =>
        maybeSummary.fold(
          MonadThrowable[F]
            .raiseError[(BlockInfo, Option[Block])](
              NotFound(s"Cannot find block matching hash ${Base16.encode(blockHash.toByteArray)}")
            )
        )(makeBlockInfo[F](_, full))
      }
    }

  def getBlockInfoOpt[F[_]: MonadThrowable: Log: MultiParentCasperRef: SafetyOracle: BlockStore](
      blockHashBase16: String,
      full: Boolean = false
  ): F[Option[(BlockInfo, Option[Block])]] =
    unsafeWithCasper[F, Option[(BlockInfo, Option[Block])]]("Could not show block.") {
      implicit casper =>
        getByHashPrefix[F, BlockSummary](blockHashBase16)(
          BlockStore[F].getBlockSummary(_)
        ).flatMap { maybeSummary =>
          maybeSummary.fold(none[(BlockInfo, Option[Block])].pure[F])(
            makeBlockInfo[F](_, full).map(_.some)
          )
        }
    }

  def getBlockInfo[F[_]: MonadThrowable: Log: MultiParentCasperRef: SafetyOracle: BlockStore](
      blockHashBase16: String,
      full: Boolean = false
  ): F[BlockInfo] =
    getBlockInfoOpt[F](blockHashBase16, full).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[BlockInfo](
            NotFound(s"Cannot find block matching hash $blockHashBase16")
          )
      )(_._1.pure[F])
    )

  /** Return block infos and maybe according blocks (if 'full' is true) in the a slice of the DAG.
    * Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfosMaybeWithBlocks[F[_]: MonadThrowable: Log: MultiParentCasperRef: SafetyOracle: BlockStore](
      depth: Int,
      maxRank: Long = 0,
      full: Boolean = false
  ): F[List[(BlockInfo, Option[Block])]] =
    unsafeWithCasper[F, List[(BlockInfo, Option[Block])]]("Could not show blocks.") {
      implicit casper =>
        casper.blockDag flatMap { dag =>
          maxRank match {
            case 0 => dag.topoSortTail(depth)
            case r => dag.topoSort(endBlockNumber = r, startBlockNumber = r - depth + 1)
          }
        } handleErrorWith {
          case ex: StorageError =>
            MonadThrowable[F].raiseError(InvalidArgument(StorageError.errorMessage(ex)))
          case ex: IllegalArgumentException =>
            MonadThrowable[F].raiseError(InvalidArgument(ex.getMessage))
        } map { ranksOfHashes =>
          ranksOfHashes.flatten.reverse.map(h => Base16.encode(h.toByteArray))
        } flatMap { hashes =>
          hashes.toList.flatTraverse(getBlockInfoOpt[F](_, full).map(_.toList))
        }
    }

  /** Return block infos in the a slice of the DAG. Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfos[F[_]: MonadThrowable: Log: MultiParentCasperRef: SafetyOracle: BlockStore](
      depth: Int,
      maxRank: Long = 0,
      full: Boolean = false
  ): F[List[BlockInfo]] =
    getBlockInfosMaybeWithBlocks[F](depth, maxRank, full).map(_.map(_._1))

  @deprecated("To be removed before devnet. Use `getBlockInfo`.", "0.4")
  def showBlock[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      q: BlockQuery
  ): F[BlockQueryResponse] = {
    val errorMessage =
      "Could not show block."

    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        maybeBlock <- getByHashPrefix[F, Block](q.hash)(
                       BlockStore[F].get(_).map(_ flatMap (_.blockMessage))
                     )
        blockQueryResponse <- maybeBlock match {
                               case Some(block) =>
                                 for {
                                   blockInfo <- getFullBlockInfo[F](block)
                                 } yield BlockQueryResponse(
                                   status = "Success",
                                   blockInfo = Some(blockInfo)
                                 )
                               case None =>
                                 BlockQueryResponse(
                                   status = s"Error: Failure to find block with hash ${q.hash}"
                                 ).pure[F]
                             }
      } yield blockQueryResponse

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      errorMessage,
      BlockQueryResponse(status = s"Error: $errorMessage").pure[F]
    )
  }

  private def getBlockInfo[A, F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: Block,
      constructor: (
          Block,
          Long,
          Int,
          BlockHash,
          Long,
          BlockHash,
          Seq[BlockHash],
          Float,
          Float
      ) => F[A]
  ): F[A] =
    for {
      dag                      <- MultiParentCasper[F].blockDag
      header                   = block.getHeader
      protocolVersion          = header.protocolVersion
      deployCount              = header.deployCount
      postStateHash            = ProtoUtil.postStateHash(block)
      timestamp                = header.timestamp
      mainParent               = header.parentHashes.headOption.getOrElse(ByteString.EMPTY)
      parentsHashList          = header.parentHashes
      normalizedFaultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, block.blockHash)
      initialFault             <- MultiParentCasper[F].normalizedInitialFault(ProtoUtil.weightMap(block))
      blockInfo <- constructor(
                    block,
                    protocolVersion,
                    deployCount,
                    postStateHash,
                    timestamp,
                    mainParent,
                    parentsHashList,
                    normalizedFaultTolerance,
                    initialFault
                  )
    } yield blockInfo

  private def getFullBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: Block
  ): F[BlockInfoWithTuplespace] =
    getBlockInfo[BlockInfoWithTuplespace, F](block, constructBlockInfo[F] _)

  private def getBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: Block
  ): F[BlockInfoWithoutTuplespace] =
    getBlockInfo[BlockInfoWithoutTuplespace, F](block, constructBlockInfoWithoutTuplespace[F] _)

  private def constructBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: Block,
      protocolVersion: Long,
      deployCount: Int,
      postStateHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfoWithTuplespace] =
    protocol
      .BlockInfo(
        blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
        blockSize = block.serializedSize.toString,
        blockNumber = ProtoUtil.blockNumber(block),
        protocolVersion = protocolVersion,
        deployCount = deployCount,
        globalStateRootHash = PrettyPrinter.buildStringNoLimit(postStateHash),
        timestamp = timestamp,
        faultTolerance = normalizedFaultTolerance - initialFault,
        mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
        parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
        sender = PrettyPrinter.buildStringNoLimit(block.getHeader.validatorPublicKey),
        shardId = block.getHeader.chainId
      )
      .pure[F]

  private def constructBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: Block,
      protocolVersion: Long,
      deployCount: Int,
      postStateHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfoWithoutTuplespace] =
    BlockInfoWithoutTuplespace(
      blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
      blockSize = block.serializedSize.toString,
      blockNumber = ProtoUtil.blockNumber(block),
      protocolVersion = protocolVersion,
      deployCount = deployCount,
      globalStateRootHash = PrettyPrinter.buildStringNoLimit(postStateHash),
      timestamp = timestamp,
      faultTolerance = normalizedFaultTolerance - initialFault,
      mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
      parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
      sender = PrettyPrinter.buildStringNoLimit(block.getHeader.validatorPublicKey)
    ).pure[F]

  private def getByHashPrefix[F[_]: Monad: MultiParentCasper: BlockStore, A](
      blockHashBase16: String
  )(f: ByteString => F[Option[A]]): F[Option[A]] =
    if (blockHashBase16.length == 64) {
      f(ByteString.copyFrom(Base16.decode(blockHashBase16)))
    } else {
      for {
        maybeHash <- BlockStore[F].findBlockHash { h =>
                      Base16.encode(h.toByteArray).startsWith(blockHashBase16)
                    }
        maybeA <- maybeHash.fold(none[A].pure[F])(f(_))
      } yield maybeA
    }

}
